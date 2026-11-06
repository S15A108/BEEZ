package com.beez.backend.changeproposal.service;

import static com.beez.backend.changeproposal.validation.ProposalValidator.requiredId;
import static com.beez.backend.designitem.support.DesignJson.hash;
import static com.beez.backend.designitem.support.DesignJson.invalid;

import com.beez.backend.ai.AiCallException;
import com.beez.backend.ai.AiClient;
import com.beez.backend.ai.AiErrorBodies;
import com.beez.backend.ai.AiUsageRecorder;
import com.beez.backend.changeproposal.repository.DraftContextRepository;
import com.beez.backend.changeproposal.repository.ProposalRepository;
import com.beez.backend.changeproposal.validation.ProposalValidator;
import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.common.concurrent.SingleFlight;
import com.beez.backend.designitem.repository.DesignProjectRepository;
import com.beez.backend.designitem.service.DesignCommandService;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Scoped drafts are suggestions only; this service never saves or publishes a document. */
@Service
public class ScopedDraftService {
    public record BlockRequest(String documentId, String baseDocumentVersionId) { }
    /** agreements and relatedProposals are caller-supplied context, not verified decisions. */
    public record ScopedRequest(String documentId, String baseDocumentVersionId, List<String> selectedBlockIds,
                                String instruction, String taskId, List<String> agreements, List<String> relatedProposals) {
        public ScopedRequest(String documentId, String baseDocumentVersionId, List<String> selectedBlockIds,
                             String instruction, String taskId) {
            this(documentId, baseDocumentVersionId, selectedBlockIds, instruction, taskId, null, null);
        }
    }
    private record Snapshot(long document, long version, Long task, Map<String, Object> source) { }
    private record Span(int start, int end, String text) { }
    private final ProjectAccessChecker access;
    private final DesignProjectRepository projects;
    private final ProposalRepository proposals;
    private final DraftContextRepository context;
    private final DesignCommandService idempotency;
    private final AiClient ai;
    private final AiUsageRecorder usage;
    private final TransactionTemplate tx;
    private final SingleFlight<Map<String, Object>> flights = new SingleFlight<>();

    public ScopedDraftService(ProjectAccessChecker access, DesignProjectRepository projects, ProposalRepository proposals,
            DraftContextRepository context, DesignCommandService idempotency, AiClient ai, AiUsageRecorder usage,
            PlatformTransactionManager manager) {
        this.access = access; this.projects = projects; this.proposals = proposals; this.context = context;
        this.idempotency = idempotency; this.ai = ai; this.usage = usage; this.tx = new TransactionTemplate(manager);
    }

    public Map<String, Object> blocks(CallerContext caller, long project, BlockRequest input) {
        if (input == null) throw invalid("요청 본문이 필요합니다.");
        access.requireMember(caller, project);
        Snapshot snapshot = tx.execute(status -> prepare(project, input.documentId(), input.baseDocumentVersionId(), null));
        Map<String, Object> reply = aiCall(() -> ai.revisionBlocks(snapshot.source()));
        parseBlocks(snapshot, reply);
        tx.executeWithoutResult(status -> checkCurrent(caller, project, snapshot));
        var result = new LinkedHashMap<>(reply);
        result.put("document_id", Long.toString(snapshot.document()));
        result.put("base_document_version_id", Long.toString(snapshot.version()));
        return result;
    }

    public Map<String, Object> generate(CallerContext caller, long project, ScopedRequest input, String key) {
        validate(input);
        access.requireMember(caller, project);
        String operation = "change-draft:scoped:" + (caller.isHuman() ? "human" : "agent:" + caller.agentTokenId());
        if (key == null || key.isBlank() || key.length() > 128 || key.chars().anyMatch(Character::isISOControl))
            throw invalid("Idempotency-Key는 제어 문자가 없는 1~128자입니다.");
        return flights.run(project + "|" + caller.accountId() + "|" + operation + "|" + key, hash(input),
                () -> generateOnce(caller, project, input, key, operation));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> generateOnce(CallerContext caller, long project, ScopedRequest input, String key, String operation) {
        Snapshot snapshot = tx.execute(status -> prepare(project, input.documentId(), input.baseDocumentVersionId(), input.taskId()));
        var previous = tx.execute(status -> {
            checkCurrent(caller, project, snapshot);
            return idempotency.replay(project, caller.accountId(), operation, key, input, Map.class);
        });
        if (previous.isPresent()) return (Map<String, Object>) previous.get();
        Map<String, Span> spans = parseBlocks(snapshot, aiCall(() -> ai.revisionBlocks(snapshot.source())));
        if (!spans.keySet().containsAll(input.selectedBlockIds())) throw conflict();
        String requestKey = "scoped-draft:" + project + ":" + caller.accountId() + ":" + UUID.randomUUID();
        var body = new LinkedHashMap<String, Object>();
        body.put("source", snapshot.source()); body.put("expected_document_version_id", Long.toString(snapshot.version()));
        body.put("selected_block_ids", input.selectedBlockIds()); body.put("instruction", input.instruction().strip());
        if (input.agreements() != null) body.put("agreements", input.agreements());
        if (input.relatedProposals() != null) body.put("related_proposals", input.relatedProposals());
        body.put("request_key", requestKey);
        long started = System.nanoTime();
        Map<String, Object> reply;
        try { reply = aiCall(() -> ai.documentRevision(body)); }
        catch (AiCallException e) {
            long ms = (System.nanoTime() - started) / 1_000_000;
            int recorded = e.usage() == null ? 0 : usage.recordLines("BEE_DRAFT", project, snapshot.task(), requestKey, e.usage(), false, null, ms);
            if (recorded == 0) usage.recordFailure("BEE_DRAFT", project, snapshot.task(), requestKey, ms);
            throw e;
        }
        catch (RuntimeException e) {
            usage.recordFailure("BEE_DRAFT", project, snapshot.task(), requestKey, (System.nanoTime() - started) / 1_000_000);
            if (e instanceof ApiException api) throw api;
            throw unavailable();
        }
        long ms = (System.nanoTime() - started) / 1_000_000;
        int recorded = reply == null ? 0 : usage.record("BEE_DRAFT", project, snapshot.task(), requestKey, reply, ms);
        Map<String, Object> output;
        try { output = checkedOutput(snapshot, input, spans, reply); }
        catch (ApiException e) {
            if (recorded == 0) usage.recordFailure("BEE_DRAFT", project, snapshot.task(), requestKey, ms);
            throw e;
        }
        return tx.execute(status -> {
            checkCurrent(caller, project, snapshot);
            var replay = idempotency.replay(project, caller.accountId(), operation, key, input, Map.class);
            if (replay.isPresent()) return (Map<String, Object>) replay.get();
            idempotency.save(project, caller.accountId(), operation, key, input, output);
            return output;
        });
    }

    private void validate(ScopedRequest input) {
        if (input == null || input.selectedBlockIds() == null || input.selectedBlockIds().isEmpty()
                || input.selectedBlockIds().size() > 30 || new HashSet<>(input.selectedBlockIds()).size() != input.selectedBlockIds().size()
                || input.selectedBlockIds().stream().anyMatch(id -> id == null || !id.matches("blk_[0-9a-f]{64}"))
                || input.instruction() == null || input.instruction().isBlank() || input.instruction().length() > 4000
                || invalidContext(input.agreements()) || invalidContext(input.relatedProposals()))
            throw invalid("수정할 범위와 요청을 확인해 주세요.");
    }

    private static boolean invalidContext(List<String> context) {
        return context != null && (context.size() > 30 || context.stream().anyMatch(s -> s == null || s.isBlank() || s.length() > 4000));
    }

    private Snapshot prepare(long project, String documentValue, String versionValue, String taskValue) {
        long document = requiredId(documentValue, "document_id"), version = requiredId(versionValue, "base_document_version_id");
        Long task = taskValue == null ? null : requiredId(taskValue, "task_id");
        if (!context.projectActive(project) || !context.documentInProject(project, document)
                || (task != null && !context.taskInProject(project, task))) throw AccessErrors.notFound("draft_context_missing");
        Long baseline = context.currentBaseline(project);
        if (baseline == null) throw conflict();
        var row = context.baselineVersion(baseline, document).orElseThrow(ScopedDraftService::conflict);
        if (!row.get("id").toString().equals(Long.toString(version))) throw conflict();
        return new Snapshot(document, version, task, ScopedDraftSnapshot.source(project, row));
    }

    private void checkCurrent(CallerContext caller, long project, Snapshot snapshot) {
        if (!projects.lockActiveProject(project)) throw AccessErrors.notFound("draft_project_missing");
        proposals.lockCaller(project, caller);
        access.requireMember(caller, project);
        var current = prepare(project, Long.toString(snapshot.document()), Long.toString(snapshot.version()),
                snapshot.task() == null ? null : snapshot.task().toString());
        if (!current.source().equals(snapshot.source())) throw conflict();
    }

    private static Map<String, Span> parseBlocks(Snapshot snapshot, Map<String, Object> reply) {
        if (reply == null || !Long.toString(snapshot.version()).equals(reply.get("document_version_id"))
                || !snapshot.source().get("text_hash").equals(reply.get("text_hash"))
                || !snapshot.source().get("parser_profile").equals(reply.get("parser_profile"))
                || !snapshot.source().get("normalization_version").equals(reply.get("normalization_version"))
                || !(reply.get("parser_version") instanceof String parser) || parser.isBlank()
                || !(reply.get("blocks") instanceof List<?> blocks)) throw unavailable();
        String text = (String) snapshot.source().get("content");
        var starts = new ArrayList<Integer>(); starts.add(0);
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') starts.add(i + 1);
        if (!text.endsWith("\n")) starts.add(text.length());
        Map<String, Span> result = new HashMap<>(); int lastEnd = 0;
        for (Object value : blocks) {
            if (!(value instanceof Map<?, ?> block) || !(block.get("block_id") instanceof String id)
                    || !id.matches("blk_[0-9a-f]{64}") || !(block.get("text") instanceof String part)
                    || !(block.get("block_type") instanceof String type) || type.isBlank()
                    || !(block.get("locator") instanceof Map<?, ?> loc) || !"text_lines".equals(loc.get("type"))
                    || !(loc.get("start_line") instanceof Integer start) || !(loc.get("end_line") instanceof Integer end)
                    || start < 1 || end < start || end >= starts.size()) throw unavailable();
            int from = starts.get(start - 1), to = starts.get(end);
            if (from < lastEnd || !text.substring(from, to).equals(part) || result.put(id, new Span(from, to, part)) != null)
                throw unavailable();
            lastEnd = to;
        }
        if (result.isEmpty()) throw unavailable();
        return result;
    }

    private static Map<String, Object> checkedOutput(Snapshot snapshot, ScopedRequest request, Map<String, Span> spans, Map<String, Object> reply) {
        if (reply == null || !"MODEL".equals(reply.get("origin")) || !(reply.get("model") instanceof String model) || model.isBlank()
                || !(reply.get("result") instanceof Map<?, ?> result)
                || !Long.toString(snapshot.version()).equals(result.get("document_version_id"))
                || !(result.get("after_content") instanceof String after) || after.isBlank()
                || !(result.get("changes") instanceof List<?> changes) || !(result.get("unresolved") instanceof List<?> unresolved)
                || unresolved.stream().anyMatch(v -> !(v instanceof String s) || s.isBlank())) throw unavailable();
        try { ProposalValidator.documentText(after); }
        catch (ApiException e) { throw unavailable(); }
        var byStart = new java.util.TreeMap<Integer, Map<?, ?>>(); var seen = new HashSet<String>();
        for (Object item : changes) {
            if (!(item instanceof Map<?, ?> change) || !(change.get("block_id") instanceof String id)
                    || !request.selectedBlockIds().contains(id) || !seen.add(id) || !spans.containsKey(id)
                    || !spans.get(id).text().equals(change.get("before")) || !(change.get("after") instanceof String)
                    || !(change.get("reason") instanceof String reason) || reason.isBlank()
                    || !(change.get("evidence") instanceof List<?> evidence) || evidence.size() > 10
                    || evidence.stream().anyMatch(quote -> !(quote instanceof String s) || s.isBlank()
                        || !containsEvidence(request, s))) throw unavailable();
            byStart.put(spans.get(id).start(), change);
        }
        if (changes.isEmpty() && unresolved.isEmpty()) throw unavailable();
        String original = (String) snapshot.source().get("content"); int cursor = 0; var composed = new StringBuilder();
        for (var entry : byStart.entrySet()) {
            var change = entry.getValue(); Span span = spans.get(change.get("block_id"));
            composed.append(original, cursor, span.start()).append(change.get("after")); cursor = span.end();
        }
        composed.append(original.substring(cursor));
        if (!after.contentEquals(composed)) throw unavailable();
        var output = new LinkedHashMap<String, Object>();
        output.put("document_id", Long.toString(snapshot.document())); output.put("base_document_version_id", Long.toString(snapshot.version()));
        output.put("after_content", after); output.put("changes", changes); output.put("unresolved", unresolved);
        var generation = new LinkedHashMap<String, Object>(); generation.put("origin", "MODEL"); generation.put("model", model);
        generation.put("usage", reply.get("usage") instanceof List<?> lines ? lines : List.of()); output.put("generation", generation);
        return output;
    }

    private static boolean containsEvidence(ScopedRequest request, String quote) {
        return (request.agreements() != null && request.agreements().stream().anyMatch(item -> item.contains(quote)))
                || (request.relatedProposals() != null && request.relatedProposals().stream().anyMatch(item -> item.contains(quote)));
    }

    private static <T> T aiCall(Supplier<T> call) {
        try { return call.get(); }
        catch (RestClientResponseException e) {
            boolean timeout = e.getStatusCode().value() == 504;
            throw new AiCallException(timeout ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY,
                    timeout ? "ai_timeout" : "ai_unavailable", "문서 수정 초안을 만들지 못했어요.", AiErrorBodies.usageIn(e));
        } catch (ResourceAccessException e) {
            boolean timeout = !(e.getCause() instanceof HttpConnectTimeoutException)
                    && (e.getCause() instanceof HttpTimeoutException || e.getCause() instanceof SocketTimeoutException);
            throw new AiCallException(timeout ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY,
                    timeout ? "ai_timeout" : "ai_unavailable", "문서 수정 초안을 만들지 못했어요.", null);
        } catch (RestClientException e) { throw unavailable(); }
    }
    private static ApiException conflict() { return new ApiException(HttpStatus.CONFLICT, "version_conflict", "기준 또는 선택 범위가 바뀌었어요. 다시 확인해 주세요."); }
    private static ApiException unavailable() { return new ApiException(HttpStatus.BAD_GATEWAY, "ai_unavailable", "문서 수정 초안을 만들지 못했어요."); }
}
