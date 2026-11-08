package com.beez.backend.meeting.service;

import static com.beez.backend.designitem.support.DesignJson.JSON;
import static com.beez.backend.designitem.support.DesignJson.hash;

import com.beez.backend.ai.AiCallException;
import com.beez.backend.ai.AiUsageRecorder;
import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.common.concurrent.SingleFlight;
import com.beez.backend.designitem.service.DesignCommandService;
import com.beez.backend.meeting.dto.MeetingEntryResponse;
import com.beez.backend.meeting.dto.MeetingGeneration;
import com.beez.backend.meeting.dto.MeetingSummaryResponse;
import com.beez.backend.meeting.dto.ReviseSummaryRequest;
import com.beez.backend.meeting.dto.SummarizeRequest;
import com.beez.backend.meeting.dto.SummaryResult;
import com.beez.backend.meeting.port.MeetingSummaryGateway;
import com.beez.backend.meeting.repository.MeetingRepository;
import com.beez.backend.meeting.repository.MeetingRepository.MeetingRow;
import com.beez.backend.meeting.repository.MeetingSummaryRepository;
import com.beez.backend.meeting.repository.MeetingSummaryRepository.SummaryRow;
import com.beez.backend.meeting.service.SummaryInputBuilder.Built;
import com.beez.backend.meeting.service.SummaryResultValidator.Context;
import com.beez.backend.meeting.service.SummaryResultValidator.Validated;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;

/**
 * MTG-A11 비비 회의 정리, MTG-A12 정리 수정 요청 (S15P31A108-235). 팀장이면서 회의 참여자만 부른다.
 *
 * <p>순서(AI 호출 동안에는 트랜잭션·회의 행 잠금을 잡지 않는다): ① 짧은 트랜잭션에서 권한·상태·입력 확인, 멱등 재생 확인, AI 입력 조립
 * → ② AI 호출 → ③ 사용량 기록(트랜잭션 밖) → ④ 출력 검증 → ⑤ 짧은 트랜잭션에서 회의 행을 잠그고 팀장·열림·최신 정리·문서 승인본을
 * 다시 확인한 뒤 SUMMARY 기록과 정리를 저장하고 멱등 기록을 남긴다.
 *
 * <p><b>실패하면 아무것도 저장하지 않는다</b>(AI 실패·쓸 수 없는 출력·호출 중 승인본 변경). 회의 기록은 그대로이고 사용량만 남는다.
 * 호출 동안 새 기록이 들어온 것은 막지 않는다 — 정리는 {@code through_entry_id} 까지만 본 것이고, 그 뒤 기록이 있으면
 * 회의 끝내기(MTG-A13)가 {@code summary_outdated} 로 막는다.
 *
 * <p><b>같은 키의 동시 요청</b>은 하나만 AI 를 부르고 나머지는 그 결과(성공이든 실패든)를 받는다({@link SingleFlight}). 이 표는 프로세스
 * 안에만 있어 백엔드 인스턴스가 둘 이상이면 서로 보지 못한다(지금은 단일 인스턴스).
 */
@Service
public class MeetingSummaryService {
    private static final Logger log = LoggerFactory.getLogger(MeetingSummaryService.class);
    static final String FEATURE = "MEETING_ANALYSIS";
    static final int MAX_FEEDBACK = 4000;
    static final int MAX_BODY = 4000;

    private final ProjectAccessChecker access;
    private final MeetingRepository meetings;
    private final MeetingSummaryRepository summaries;
    private final MeetingAssembler assembler;
    private final SummaryInputBuilder inputs;
    private final MeetingSummaryGateway gateway;
    private final AiUsageRecorder usage;
    private final DesignCommandService idempotency;
    private final TransactionTemplate transaction;
    /** 진행 중인 요청. 키는 프로젝트·계정·작업 종류·Idempotency-Key */
    private final SingleFlight<MeetingSummaryResponse> flights = new SingleFlight<>(
            () -> new ApiException(HttpStatus.GATEWAY_TIMEOUT, "ai_timeout", AiMeetingSummaryAdapter.TIMEOUT),
            MeetingSummaryService::unavailable);

    public MeetingSummaryService(ProjectAccessChecker access, MeetingRepository meetings, MeetingSummaryRepository summaries,
                                 MeetingAssembler assembler, SummaryInputBuilder inputs, MeetingSummaryGateway gateway,
                                 AiUsageRecorder usage, DesignCommandService idempotency, PlatformTransactionManager manager) {
        this.access = access;
        this.meetings = meetings;
        this.summaries = summaries;
        this.assembler = assembler;
        this.inputs = inputs;
        this.gateway = gateway;
        this.usage = usage;
        this.idempotency = idempotency;
        this.transaction = new TransactionTemplate(manager);
    }

    /** 멱등 지문으로 쓰는 요청 내용 */
    private record SummarizeInput(long throughEntryId) { }
    private record ReviseInput(long summaryId, String feedback) { }

    /** 요청 한 건. summaryId 가 있으면 수정 요청(A12) */
    private record Command(ProjectActor actor, long projectId, long meetingId, Long summaryId, Long throughEntryId,
                           String feedback, String operation, Object input) { }

    /** AI 에 보내기 전에 확인한 값과, 호출 뒤 출력을 검증·대조할 때 쓰는 값 */
    private record Prepared(Built built, long throughEntryId, Long previousSummaryId, String feedback) { }
    private record Step(MeetingSummaryResponse replayed, Prepared prepared) { }

    /** MTG-A11 */
    public MeetingSummaryResponse summarize(CallerContext caller, long projectId, String meetingIdRaw, SummarizeRequest body, String key) {
        ProjectActor actor = requireLeader(caller, projectId);
        requireBaseline(projectId);
        long meetingId = Ids.parse(meetingIdRaw, "meeting_id");
        if (body == null || body.throughEntryId() == null || body.throughEntryId().isBlank()) throw invalid("through_entry_id 가 필요합니다");
        long through = Ids.parse(body.throughEntryId().strip(), "through_entry_id");
        Command command = new Command(actor, projectId, meetingId, null, through, null, "meeting:summary:" + meetingId,
                new SummarizeInput(through));
        return dispatch(command, key);
    }

    /** MTG-A12 */
    public MeetingSummaryResponse revise(CallerContext caller, long projectId, String meetingIdRaw, String summaryIdRaw,
                                         ReviseSummaryRequest body, String key) {
        ProjectActor actor = requireLeader(caller, projectId);
        requireBaseline(projectId);
        long meetingId = Ids.parse(meetingIdRaw, "meeting_id");
        long summaryId = Ids.parse(summaryIdRaw, "summary_id");
        if (body == null || body.feedback() == null || body.feedback().isBlank()) throw invalid("고칠 점이 필요합니다");
        String feedback = body.feedback().strip();
        if (feedback.length() > MAX_FEEDBACK) throw invalid("고칠 점은 " + MAX_FEEDBACK + "자 이하여야 합니다");
        Command command = new Command(actor, projectId, meetingId, summaryId, null, feedback,
                "meeting:summary-revise:" + meetingId + ":" + summaryId, new ReviseInput(summaryId, feedback));
        return dispatch(command, key);
    }

    private MeetingSummaryResponse dispatch(Command command, String key) {
        // 키가 올바르지 않으면 공유 표에 올리지 않는다 — 곧 멱등 확인이 400 으로 거절한다
        if (key == null || key.isBlank() || key.length() > 128) return run(command, key);
        String flightKey = command.projectId() + "|" + command.actor().accountId() + "|" + command.operation() + "|" + key;
        return flights.run(flightKey, hash(command.input()), () -> run(command, key));
    }

    private MeetingSummaryResponse run(Command command, String key) {
        long projectId = command.projectId();
        Step step = transaction.execute(status -> prepare(command, key));
        if (step.replayed() != null) return step.replayed();
        Prepared prepared = step.prepared();

        // 호출마다 새 key: 같은 요청을 다시 보내도 실제로 일어난 AI 호출은 모두 사용량에 센다
        String requestKey = "ai-a08:" + projectId + ":" + command.actor().accountId() + ":" + UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("request_key", requestKey);
        payload.putAll(prepared.built().payload());
        long started = System.nanoTime();
        Map<String, Object> response;
        try {
            response = gateway.summarize(payload);
        } catch (ApiException e) {
            recordFailedCall(projectId, requestKey, e, elapsedMillis(started));
            throw e;
        } catch (RuntimeException e) {
            log.error("AI-A08 call failed unexpectedly: {}", e.toString(), e);
            usage.recordFailure(FEATURE, projectId, null, requestKey, elapsedMillis(started));
            throw unavailable();
        }
        long elapsed = elapsedMillis(started);
        int recorded = response == null ? 0 : usage.record(FEATURE, projectId, null, requestKey, response, elapsed);
        Validated validated;
        try {
            validated = SummaryResultValidator.validate(response, new Context(prepared.built().entryIds(), prepared.built().documents(),
                    member -> access.isActiveMember(member, projectId)));
        } catch (ApiException e) {
            if (recorded == 0) usage.recordFailure(FEATURE, projectId, null, requestKey, elapsed);
            throw e;
        }
        return transaction.execute(status -> save(command, key, prepared, validated));
    }

    // ---- ① 확인과 입력 조립 ----

    private Step prepare(Command command, String key) {
        long projectId = command.projectId();
        long meetingId = command.meetingId();
        ProjectActor actor = command.actor();
        MeetingRow meeting = meetings.findMeeting(projectId, meetingId).orElseThrow(() -> missing(projectId, meetingId));
        requireParticipant(meetingId, actor);
        Optional<MeetingSummaryResponse> replay = idempotency.replay(projectId, actor.accountId(), command.operation(), key,
                command.input(), MeetingSummaryResponse.class);
        if (replay.isPresent()) return new Step(replay.get(), null);
        requireOpen(meeting);

        long through;
        Long previousId = null;
        Map<String, Object> previous = null;
        if (command.summaryId() == null) {
            through = command.throughEntryId();
            String kind = meetings.entryKind(meetingId, through)
                    .orElseThrow(() -> AccessErrors.notFound("meeting_entry_missing meeting=" + meetingId + " entry=" + command.throughEntryId()));
            if (!kind.equals("TEXT") && !kind.equals("CONFLICT_BRIEF")) throw invalid("정리에 쓸 수 있는 기록이 아닙니다");
        } else {
            SummaryRow target = summaries.find(meetingId, command.summaryId())
                    .orElseThrow(() -> AccessErrors.notFound("meeting_summary_missing meeting=" + meetingId + " summary=" + command.summaryId()));
            requireLatest(meetingId, target.id());
            previousId = target.id();
            previous = JSON.convertValue(toResponse(projectId, target), new TypeReference<Map<String, Object>>() { });
            // 다시 정리할 때는 지금까지의 모든 기록을 본다 — 이전 정리 뒤에 오간 말도 반영된다
            through = meetings.lastSummarizableEntryId(meetingId)
                    .orElseThrow(() -> invalid("정리할 기록이 없습니다"));
        }
        Built built = inputs.build(CallerContext.human(actor.accountId()), projectId, meetingId, through, previous, command.feedback());
        return new Step(null, new Prepared(built, through, previousId, command.feedback()));
    }

    // ---- ⑤ 저장 ----

    private MeetingSummaryResponse save(Command command, String key, Prepared prepared, Validated validated) {
        long projectId = command.projectId();
        long meetingId = command.meetingId();
        ProjectActor actor = command.actor();
        MeetingRow meeting = meetings.lockMeeting(projectId, meetingId).orElseThrow(() -> missing(projectId, meetingId));
        // 잠금을 기다리는 동안 팀장이 바뀌었거나 회의에서 빠졌으면 여기서 403
        access.requireLeaderAfterLock(actor);
        requireParticipant(meetingId, actor);
        Optional<MeetingSummaryResponse> replay = idempotency.replay(projectId, actor.accountId(), command.operation(), key,
                command.input(), MeetingSummaryResponse.class);
        if (replay.isPresent()) return replay.get();
        requireOpen(meeting);
        if (prepared.previousSummaryId() != null) requireLatest(meetingId, prepared.previousSummaryId());
        // 호출 동안 다른 제안이 발행돼 정리가 인용한 문서의 승인본이 바뀌었으면 저장하지 않는다
        for (SummaryResult.DocumentChange change : validated.result().documentChanges()) {
            Optional<Long> current = summaries.baselineDocumentVersion(projectId, Long.parseLong(change.documentId()));
            if (current.isEmpty() || current.get() != Long.parseLong(change.baseVersionId())) {
                throw new ApiException(HttpStatus.CONFLICT, "summary_outdated", "기준이 바뀌었어요. 다시 정리해 주세요.");
            }
        }

        List<String> unresolved = new java.util.ArrayList<>(prepared.built().notes());
        unresolved.addAll(validated.unresolved());
        String body = body(validated.result());
        MeetingGeneration generation = new MeetingGeneration(validated.origin(), validated.model(),
                validated.text() != null ? validated.text() : body, validated.sources(), unresolved, validated.usage());

        long entryId = meetings.insertEntry(meetingId, "SUMMARY", "BEE", null, body, null);
        long summaryId = summaries.insert(meetingId, projectId, entryId, prepared.throughEntryId(), prepared.previousSummaryId(),
                prepared.feedback(), JSON.writeValueAsString(validated.result()), JSON.writeValueAsString(generation),
                actor.accountId());
        MeetingSummaryResponse response = toResponse(projectId, summaries.find(meetingId, summaryId).orElseThrow());
        idempotency.save(projectId, actor.accountId(), command.operation(), key, command.input(), response);
        return response;
    }

    private MeetingSummaryResponse toResponse(long projectId, SummaryRow row) {
        SummaryResult result = JSON.readValue(row.result(), SummaryResult.class);
        MeetingGeneration generation = MeetingAssembler.generation(row.generation());
        MeetingEntryResponse entry = MeetingAssembler.toResponse(meetings.findEntry(projectId, row.entryId()).orElseThrow(),
                Ids.str(row.id()), Ids.str(row.throughEntryId()), generation);
        return new MeetingSummaryResponse(Ids.str(row.id()), Ids.str(row.meetingId()), entry, Ids.str(row.throughEntryId()),
                result.conflicts(), result.decisions(), result.documentChanges(), result.actionItems(),
                Ids.str(row.previousSummaryId()), row.feedback(), generation, row.createdAt());
    }

    /** SUMMARY 기록 본문 — 사람이 읽는 요약. 구조화된 값은 정리 응답에 전부 있다 */
    static String body(SummaryResult r) {
        StringBuilder b = new StringBuilder("회의 정리");
        list(b, "충돌", r.conflicts());
        list(b, "결정", r.decisions().stream().map(SummaryResult.Decision::text).toList());
        long documents = r.documentChanges().stream().map(SummaryResult.DocumentChange::documentId).distinct().count();
        b.append("\n바뀔 문서: ").append(r.documentChanges().isEmpty() ? "없음"
                : "문서 " + documents + "건 · 변경 " + r.documentChanges().size() + "곳");
        list(b, "할 일", r.actionItems().stream().map(SummaryResult.ActionItem::text).toList());
        return b.length() > MAX_BODY ? b.substring(0, MAX_BODY - 1) + "…" : b.toString();
    }

    private static void list(StringBuilder b, String label, List<String> items) {
        if (items.isEmpty()) {
            b.append('\n').append(label).append(": 없음");
            return;
        }
        b.append('\n').append(label).append(':');
        for (String item : items) b.append("\n- ").append(item);
    }

    // ---- 확인 ----

    /**
     * MTG-A15. 회의의 최신 정리를 읽는다(구조화한 값 전부). 새로 고치거나 다른 기기·다른 팀장이 열어도 끝내기·수정 요청에 필요한
     * summary_id 와 정리 내용을 다시 받을 수 있게 한다. 프로젝트 멤버면 읽고, 정리가 아직 없으면 404 다.
     */
    @Transactional(readOnly = true)
    public MeetingSummaryResponse latest(CallerContext caller, long projectId, String meetingIdRaw) {
        access.requireMember(caller, projectId);
        requireBaseline(projectId);
        long meetingId = Ids.parse(meetingIdRaw, "meeting_id");
        meetings.findMeeting(projectId, meetingId).orElseThrow(() -> missing(projectId, meetingId));
        return toResponse(projectId, summaries.findLatest(meetingId)
                .orElseThrow(() -> AccessErrors.notFound("summary_missing project=" + projectId + " meeting=" + meetingId)));
    }

    /** 팀장(사람)만. 팀원은 403 */
    private ProjectActor requireLeader(CallerContext caller, long projectId) {
        ProjectActor actor = access.requireMember(caller, projectId);
        actor.requireHuman();
        if (!actor.isLeader()) {
            throw AccessErrors.forbidden("role_required_leader project=" + projectId + " member=" + actor.memberId(), "팀장만 요청할 수 있어요");
        }
        return actor;
    }

    private void requireParticipant(long meetingId, ProjectActor actor) {
        if (!meetings.isParticipant(meetingId, actor.accountId())) {
            throw AccessErrors.forbidden("not_meeting_participant meeting=" + meetingId + " member=" + actor.memberId(),
                    "회의 참여자만 요청할 수 있어요");
        }
    }

    private void requireBaseline(long projectId) {
        if (!meetings.hasBaseline(projectId)) {
            throw new ApiException(HttpStatus.CONFLICT, "baseline_missing", "승인된 기준 설계가 아직 없습니다");
        }
    }

    private void requireOpen(MeetingRow meeting) {
        if (!"OPEN".equals(meeting.status())) throw new ApiException(HttpStatus.CONFLICT, "meeting_closed", "끝난 회의입니다");
    }

    /** 이 회의의 최신 정리여야 한다 */
    private void requireLatest(long meetingId, long summaryId) {
        Optional<SummaryRow> latest = summaries.findLatest(meetingId);
        if (latest.isEmpty() || latest.get().id() != summaryId) {
            throw new ApiException(HttpStatus.CONFLICT, "summary_outdated", "최신 정리가 아니에요. 새로 고친 뒤 다시 요청해 주세요.");
        }
    }

    /**
     * 실패한 호출의 사용량. 모델 호출이 일어난 뒤의 실패라 AI 가 오류 본문에 실제 usage 를 실었으면 그 줄들을 남기고,
     * 본문을 받지 못했거나 usage 가 없을 때만 토큰 수를 모르는 실패 한 줄을 남긴다(0 으로 단정하지 않는다).
     */
    private void recordFailedCall(long projectId, String requestKey, ApiException error, long elapsed) {
        int recorded = 0;
        if (error instanceof AiCallException call && call.usage() != null) {
            recorded = usage.recordLines(FEATURE, projectId, null, requestKey, call.usage(), false, null, elapsed);
        }
        if (recorded == 0) usage.recordFailure(FEATURE, projectId, null, requestKey, elapsed);
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private ApiException missing(long projectId, long meetingId) {
        return AccessErrors.notFound("meeting_missing project=" + projectId + " meeting=" + meetingId);
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", message);
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "ai_unavailable", AiMeetingSummaryAdapter.UNAVAILABLE);
    }
}
