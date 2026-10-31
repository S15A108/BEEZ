package com.beez.backend.document.service;

import static com.beez.backend.document.service.DocumentValues.*;

import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.*;
import com.beez.backend.document.entity.DocumentVersion;
import com.beez.backend.document.repository.*;
import com.beez.backend.document.storage.DocumentStorage;
import com.beez.backend.document.validation.DocumentInputValidator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DocumentApplicationService {
    private final JdbcClient jdbc;
    private final ProjectAccessChecker access;
    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final DocumentStorage storage;
    private final DocumentInputValidator validator;
    private final PlatformTransactionManager transactions;
    private final TransactionTemplate transaction;
    private final DocumentOriginalWriter originals;
    private final DocumentIndexWorker worker;
    private final DocumentReviewRepository reviews;

    public DocumentApplicationService(JdbcClient jdbc, ProjectAccessChecker access, DocumentRepository documents,
            DocumentVersionRepository versions, DocumentStorage storage, DocumentInputValidator validator,
            PlatformTransactionManager transactions, DocumentIndexWorker worker, DocumentReviewRepository reviews) {
        this.jdbc = jdbc; this.access = access; this.documents = documents; this.versions = versions;
        this.storage = storage; this.validator = validator; this.transactions = transactions; this.worker = worker;
        this.transaction = new TransactionTemplate(transactions);
        this.originals = new DocumentOriginalWriter(storage, transactions);
        this.reviews = reviews;
    }

    private List<Map<String, Object>> rows(String sql, Map<String, ?> parameters) {
        return jdbc.sql(sql).params(parameters).query().listOfRows();
    }
    private Map<String, Object> one(String sql, Map<String, ?> parameters) {
        return rows(sql, parameters).stream().findFirst().orElseThrow(DocumentValues::missing);
    }
    private void lock(long project) { if (documents.lockProject(project) == null) throw missing(); }
    private void requireAuthor(CallerContext caller, long project, long id) {
        access.requireMember(caller, project).requireHuman();
        var doc = document(project, id);
        if (number(doc, "created_by") != caller.accountId()) {
            throw com.beez.backend.common.access.AccessErrors.forbidden("document_author_required", "등록한 담당자만 문서를 수정할 수 있습니다.");
        }
        reviews.workingCopy(project, id).ifPresent(copy -> {
            if ("SUBMITTED".equals(copy.get("status"))) throw com.beez.backend.designitem.support.DesignJson.conflict("submission_locked");
        });
    }
    private String dbKind(DocumentKind kind) { return new DocumentKindMapper().convertToDatabaseColumn(kind); }
    private Map<String, Object> document(long project, long id) {
        return one("SELECT * FROM document WHERE id=:id AND project_id=:project", Map.of("id", id, "project", project));
    }
    private Map<String, Object> version(long document, long id) {
        return one("SELECT * FROM document_version WHERE document_id=:document AND id=:id", Map.of("document", document, "id", id));
    }
    private Optional<Map<String, Object>> approved(long document) {
        return rows("""
                SELECT v.* FROM document_version v WHERE v.document_id=:document AND v.status='APPROVED'
                ORDER BY v.version_no DESC LIMIT 1
                """, Map.of("document", document)).stream().findFirst();
    }
    private Map<String, Object> versionNumber(long document, int no) {
        if (no < 1) throw invalid("version_no는 양수입니다.");
        return one("SELECT * FROM document_version WHERE document_id=:document AND version_no=:no", Map.of("document", document, "no", no));
    }

    private Map<String, Object> versionView(Map<String, Object> row, boolean content) {
        String extension = row.get("storage_key").toString().endsWith(".md") ? "md" : "txt";
        var result = new LinkedHashMap<String, Object>();
        result.put("id", row.get("id").toString()); result.put("document_id", row.get("document_id").toString());
        result.put("version_no", row.get("version_no")); result.put("status", row.get("status"));
        result.put("file_name", "document-" + row.get("document_id") + "-v" + row.get("version_no") + "." + extension);
        result.put("media_type", extension.equals("md") ? "text/markdown" : "text/plain");
        result.put("content_hash", row.get("content_hash")); result.put("created_at", time(row.get("created_at")));
        if (content && row.get("content_text") != null) result.put("content", row.get("content_text"));
        return result;
    }
    private Map<String, Object> documentView(Map<String, Object> row) {
        long id = number(row, "id");
        var result = new LinkedHashMap<String, Object>();
        result.put("id", Long.toString(id)); result.put("project_id", row.get("project_id").toString());
        result.put("kind", new DocumentKindMapper().convertToEntityAttribute(row.get("type").toString()).name());
        result.put("title", row.get("title"));
        if (row.get("domain") != null) result.put("domain", row.get("domain"));
        approved(id).ifPresent(v -> result.put("approved_version", versionView(v, false)));
        rows("SELECT * FROM document_version WHERE document_id=:id AND status='DRAFT' ORDER BY version_no DESC LIMIT 1", Map.of("id", id))
                .stream().findFirst().ifPresent(v -> result.put("latest_draft", versionView(v, false)));
        return result;
    }

    private String fingerprint(Object value) { return hash(JSON.writeValueAsBytes(value)); }
    @SuppressWarnings("unchecked")
    private Optional<Map<String, Object>> replay(CallerContext caller, long project, String operation, String key, String fingerprint) {
        if (key == null || key.isBlank() || key.length() > 128) throw invalid("Idempotency-Key는 1~128자입니다.");
        var params = Map.of("project", project, "account", caller.accountId(), "operation", operation, "key", key);
        jdbc.sql("DELETE FROM document_http_command WHERE project_id=:project AND account_id=:account AND operation=:operation AND request_key=:key AND expires_at<=now()")
                .params(params).update();
        var previous = rows("SELECT fingerprint,response::text AS response FROM document_http_command WHERE project_id=:project AND account_id=:account AND operation=:operation AND request_key=:key", params);
        if (previous.isEmpty()) return Optional.empty();
        if (!fingerprint.equals(previous.getFirst().get("fingerprint"))) throw conflict("idempotency_conflict");
        return Optional.of(JSON.readValue(previous.getFirst().get("response").toString(), Map.class));
    }
    private Map<String, Object> remember(CallerContext caller, long project, String operation, String key, String fingerprint, Map<String, Object> response) {
        jdbc.sql("INSERT INTO document_http_command(project_id,account_id,operation,request_key,fingerprint,response) VALUES(:project,:account,:operation,:key,:fingerprint,cast(:response AS jsonb))")
                .params(Map.of("project", project, "account", caller.accountId(), "operation", operation, "key", key,
                        "fingerprint", fingerprint, "response", JSON.writeValueAsString(response))).update();
        return response;
    }
    private void enqueue(long version, String textHash) {
        jdbc.sql("INSERT INTO document_processing(document_version_id,status,text_hash) VALUES(:id,'PENDING',:hash)")
                .param("id", version).param("hash", textHash).update();
    }

    @Transactional(propagation = Propagation.NEVER)
    public Map<String, Object> upload(CallerContext caller, long project, DocumentKind kind, String title,
            String domain, String filename, byte[] bytes, String key) {
        access.requireMember(caller, project).requireHuman();
        var input = new DocumentRegistrationService.Input(project, kind, title, domain, filename, bytes);
        DocumentRegistrationService.validateInput(input);
        byte[] originalBytes = bytes.clone();
        var source = validator.validate(filename, originalBytes);
        String fp = fingerprint(Arrays.asList(kind, title, domain, source.extension(), source.contentHash()));
        var registration = new DocumentRegistrationService(validator, storage,
                id -> { access.requireMember(caller, id).requireHuman(); return caller.accountId(); }, documents, versions,
                transactions);
        var previous = transaction.execute(tx -> {
            access.requireMember(caller, project).requireHuman();
            var response = replay(caller, project, "upload", key, fp);
            if (response.isEmpty()) registration.checkRegistration(input);
            return response;
        });
        if (previous.isPresent()) return previous.get();
        return originals.write(originalBytes, source.extension(), original -> {
            lock(project); access.requireMember(caller, project).requireHuman();
            if (!reviews.lockMember(project, caller.accountId(), false)) throw missing();
            var concurrent = replay(caller, project, "upload", key, fp);
            if (concurrent.isPresent()) return concurrent.get();
            var saved = registration.persist(input, source, original);
            reviews.initialize(saved.documentId(), saved.documentVersionId(), caller.accountId(), filename);
            enqueue(saved.documentVersionId(), saved.textHash());
            var response = Map.<String, Object>of("document", documentView(document(project, saved.documentId())), "analysis_status", "PENDING");
            return remember(caller, project, "upload", key, fp, response);
        });
    }

    public Map<String, Object> list(CallerContext caller, long project, DocumentKind kind, String domain, String cursor, int limit) {
        access.requireMember(caller, project); size(limit);
        String sql = "SELECT * FROM document WHERE project_id=:project";
        var params = new HashMap<String, Object>(Map.of("project", project, "limit", limit + 1));
        if (kind != null) { sql += " AND type=:kind"; params.put("kind", dbKind(kind)); }
        if (domain != null) { sql += " AND domain=:domain"; params.put("domain", domain); }
        sql += cursorWhere(cursor, params, false);
        var all = rows(sql + " ORDER BY created_at DESC,id DESC LIMIT :limit", params);
        boolean more = all.size() > limit; var selected = all.subList(0, Math.min(limit, all.size()));
        return page(selected.stream().map(this::documentView).toList(), more, more ? cursor(selected.getLast()) : null);
    }
    private String cursorWhere(String encoded, Map<String, Object> params, boolean typed) {
        if (encoded == null) return "";
        String[] parts = cursor(encoded); params.put("before_time", parts[0]); params.put("before_id", Long.parseLong(parts[1]));
        if (typed) { params.put("before_type", parts[2]); return " AND (created_at,id,type)<(cast(:before_time AS timestamptz),:before_id,:before_type)"; }
        return " AND (created_at,id)<(cast(:before_time AS timestamptz),:before_id)";
    }
    public Map<String, Object> get(CallerContext caller, long project, long id, Integer no) {
        access.requireMember(caller, project); var doc = document(project, id);
        var selected = no == null ? approved(id).orElseThrow(() -> com.beez.backend.designitem.support.DesignJson.conflict("document_not_approved")) : versionNumber(id, no);
        return Map.of("document", documentView(doc), "version", versionView(selected, true));
    }
    public Map<String, Object> versions(CallerContext caller, long project, long id, String cursor, int limit) {
        access.requireMember(caller, project); document(project, id); size(limit);
        var params = new HashMap<String, Object>(Map.of("document", id, "limit", limit + 1));
        var all = rows("SELECT * FROM document_version WHERE document_id=:document" + cursorWhere(cursor, params, false)
                + " ORDER BY created_at DESC,id DESC LIMIT :limit", params);
        boolean more = all.size() > limit; var selected = all.subList(0, Math.min(limit, all.size()));
        return page(selected.stream().map(v -> versionView(v, false)).toList(), more, more ? cursor(selected.getLast()) : null);
    }
    public record Download(String filename, String mediaType, byte[] bytes) { }
    public Download download(CallerContext caller, long project, long id, int no) throws IOException {
        access.requireMember(caller, project); document(project, id); var row = versionNumber(id, no); var view = versionView(row, false);
        return new Download(view.get("file_name").toString(), view.get("media_type").toString(), storage.read(row.get("storage_key").toString()));
    }
    public Map<String, Object> diff(CallerContext caller, long project, long id, int from, int to) {
        access.requireMember(caller, project); document(project, id);
        var a = versionNumber(id, from); var b = versionNumber(id, to);
        String aText = Objects.toString(a.get("content_text"), ""); String bText = Objects.toString(b.get("content_text"), "");
        String difference = com.beez.backend.common.diff.LineDiff.compare(aText, bText);
        return Map.of("from_version", from, "to_version", to, "format", "TEXT", "diff", difference,
                "before_hash", a.get("content_hash"), "after_hash", b.get("content_hash"));
    }

    @Transactional
    public Map<String, Object> classify(CallerContext caller, long project, long id, Map<String, String> body, String key) {
        requireAuthor(caller, project, id); lock(project); requireAuthor(caller, project, id);
        if (!reviews.lockMember(project, caller.accountId(), false)) throw missing();
        if (body == null || body.size() != 1 || !body.containsKey("domain") || body.get("domain") == null) throw invalid("domain만 수정할 수 있습니다.");
        String operation = "classify:" + id, fp = fingerprint(body);
        var previous = replay(caller, project, operation, key, fp); if (previous.isPresent()) return previous.get();
        jdbc.sql("UPDATE document SET domain=:domain,updated_at=now() WHERE id=:id").param("domain", body.get("domain")).param("id", id).update();
        return remember(caller, project, operation, key, fp, documentView(document(project, id)));
    }

    @Transactional(propagation = Propagation.NEVER)
    public Map<String, Object> draft(CallerContext caller, long project, long id, long base,
            String filename, byte[] bytes, String key) {
        access.requireMember(caller, project); document(project, id);
        byte[] originalBytes = bytes.clone();
        var source = validator.validate(filename, originalBytes);
        String operation = "draft:" + id, fp = fingerprint(List.of(base, source.extension(), source.contentHash()));
        var previous = transaction.execute(tx -> {
            access.requireMember(caller, project); document(project, id);
            var response = replay(caller, project, operation, key, fp);
            if (response.isEmpty()) requireCurrentBase(id, base);
            return response;
        });
        if (previous.isPresent()) return previous.get();
        return originals.write(originalBytes, source.extension(), original -> {
            lock(project); access.requireMember(caller, project); document(project, id);
            var concurrent = replay(caller, project, operation, key, fp);
            if (concurrent.isPresent()) return concurrent.get();
            requireCurrentBase(id, base);
            int no = jdbc.sql("SELECT coalesce(max(version_no),0)+1 FROM document_version WHERE document_id=:id").param("id", id).query(Integer.class).single();
            var saved = versions.saveAndFlush(DocumentVersion.draft(id, no, original.reference(), source.contentHash(), source.text(), caller.accountId(), base));
            enqueue(saved.getId(), source.textHash());
            return remember(caller, project, operation, key, fp, versionView(version(id, saved.getId()), true));
        });
    }

    private void requireCurrentBase(long document, long base) {
        // DOC-A12 is based on the development baseline, not the document management screen's latest approval.
        var current = rows("""
                SELECT v.* FROM document_version v JOIN document d ON d.id=v.document_id
                JOIN project p ON p.id=d.project_id
                JOIN baseline_document b ON b.baseline_id=p.current_baseline_id AND b.document_id=d.id
                  AND b.document_version_id=v.id WHERE d.id=:document
                """, Map.of("document", document)).stream().findFirst().orElseThrow(() -> conflict("version_conflict"));
        if (number(current, "id") != base) throw conflict("version_conflict");
    }

    public Map<String, Object> analysis(CallerContext caller, long project, long id, long version) {
        access.requireMember(caller, project); document(project, id); version(id, version);
        var run = one("SELECT * FROM document_processing WHERE document_version_id=:id", Map.of("id", version));
        var response = new LinkedHashMap<String, Object>();
        response.put("document_version_id", Long.toString(version)); response.put("status", run.get("status"));
        response.put("source_status", run.get("source_status")); response.put("index_status", run.get("index_status"));
        response.put("attempt", run.get("attempt")); response.put("items", List.of()); response.put("questions", List.of());
        if (run.get("error_code") != null) response.put("error_code", run.get("error_code"));
        response.put("message", switch (run.get("status").toString()) {
            case "PENDING", "RUNNING" -> "분석 중입니다."; case "FAILED" -> "분석에 실패했습니다. 재시도할 수 있습니다.";
            default -> "검색 준비가 완료되었습니다.";
        });
        return response;
    }
    @Transactional
    public Map<String, Object> retry(CallerContext caller, long project, long id, long version, String key) {
        access.requireHumanLeader(caller, project); lock(project); access.requireHumanLeader(caller, project); document(project, id); version(id, version);
        String operation = "retry:" + version, fp = fingerprint(version);
        var previous = replay(caller, project, operation, key, fp); if (previous.isPresent()) return previous.get();
        var run = one("SELECT * FROM document_processing WHERE document_version_id=:id FOR UPDATE", Map.of("id", version));
        if (List.of("PENDING", "RUNNING").contains(run.get("status"))) throw conflict("analysis_running");
        if (!"FAILED".equals(run.get("status"))) throw conflict("analysis_not_failed");
        jdbc.sql("UPDATE document_processing SET attempt=attempt+1,automatic_recoveries=0,status='PENDING',source_status='PENDING',index_status='PENDING',error_code=null,lease_token=null,lease_until=null,updated_at=now() WHERE document_version_id=:id")
                .param("id", version).update();
        return remember(caller, project, operation, key, fp, analysis(caller, project, id, version));
    }

    public Map<String, Object> search(CallerContext caller, long project, String query, String mode, String domain,
            String kind, String cursor, int limit) {
        long start = System.nanoTime(); access.requireMember(caller, project); size(limit);
        if (query == null || query.isBlank() || query.length() > 1000) throw invalid("검색어는 1~1000자입니다.");
        if ("SEMANTIC".equals(mode)) {
            var result = worker.search(project, query, domain, kind, cursor, limit);
            access.requireMember(caller, project);
            return result;
        }
        if (!"KEYWORD".equals(mode)) throw invalid("KEYWORD 또는 SEMANTIC 검색입니다.");
        String sql = """
                SELECT * FROM (
                 SELECT 'DOCUMENT'::text AS type,d.id,d.title,d.domain,d.type AS kind,v.created_at,
                  d.id AS document_id,v.id AS document_version_id,v.version_no,v.content_text AS body,NULL::bigint AS design_item_id
                 FROM project p JOIN baseline_document b ON b.baseline_id=p.current_baseline_id
                 JOIN document d ON d.id=b.document_id AND d.project_id=p.id
                 JOIN document_version v ON v.id=b.document_version_id AND v.document_id=d.id WHERE p.id=:project
                 UNION ALL
                 SELECT 'DESIGN_ITEM',i.id,v.title,i.domain,i.kind,v.created_at,
                  dv.document_id,dv.id,dv.version_no,v.content::text,i.id
                 FROM project p JOIN baseline_item b ON b.baseline_id=p.current_baseline_id
                 JOIN design_item i ON i.id=b.design_item_id AND i.project_id=p.id
                 JOIN design_item_version v ON v.id=b.design_item_version_id AND v.design_item_id=i.id
                 LEFT JOIN baseline_document bd ON bd.baseline_id=p.current_baseline_id AND bd.document_version_id=v.source_document_version_id
                 LEFT JOIN document_version dv ON dv.id=bd.document_version_id WHERE p.id=:project
                ) hits WHERE (position(lower(:q) IN lower(title))>0 OR position(lower(:q) IN lower(body))>0)
                """;
        var params = new HashMap<String, Object>(Map.of("project", project, "q", query, "limit", limit + 1));
        if (domain != null) { sql += " AND domain=:domain"; params.put("domain", domain); }
        if (kind != null) { sql += " AND kind=:kind"; params.put("kind", "REQUIREMENTS".equals(kind) ? "REQ_SPEC" : kind); }
        sql += cursorWhere(cursor, params, true);
        var all = rows(sql + " ORDER BY created_at DESC,id DESC,type DESC LIMIT :limit", params);
        boolean more = all.size() > limit; var selected = all.subList(0, Math.min(limit, all.size()));
        var result = page(selected.stream().map(this::hit).toList(), more, more ? cursor(selected.getLast()) : null);
        result.put("elapsed_ms", (System.nanoTime() - start) / 1_000_000); return result;
    }
    private Map<String, Object> hit(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        result.put("type", row.get("type")); result.put("id", row.get("id").toString()); result.put("title", row.get("title"));
        String body = Objects.toString(row.get("body"), ""); result.put("snippet", body.substring(0, Math.min(300, body.length())));
        if (row.get("document_version_id") != null) result.put("source", Map.of("document_id", row.get("document_id").toString(),
                "document_version_id", row.get("document_version_id").toString(), "version_no", row.get("version_no"), "location", "원문"));
        if (row.get("design_item_id") != null) result.put("design_item_id", row.get("design_item_id").toString());
        return result;
    }

    public Map<String, Object> baseline(CallerContext caller, long project, Integer no) {
        access.requireMember(caller, project);
        var row = no == null
                ? one("SELECT b.* FROM baseline b JOIN project p ON p.current_baseline_id=b.id AND p.id=b.project_id WHERE p.id=:project", Map.of("project", project))
                : one("SELECT * FROM baseline WHERE project_id=:project AND version_no=:no", Map.of("project", project, "no", no));
        return baselineView(row);
    }
    public Map<String, Object> baselines(CallerContext caller, long project, String cursor, int limit) {
        access.requireMember(caller, project); size(limit);
        var params = new HashMap<String, Object>(Map.of("project", project, "limit", limit + 1));
        var all = rows("SELECT * FROM baseline WHERE project_id=:project" + cursorWhere(cursor, params, false)
                + " ORDER BY created_at DESC,id DESC LIMIT :limit", params);
        boolean more = all.size() > limit; var selected = all.subList(0, Math.min(limit, all.size()));
        return page(selected.stream().map(this::baselineView).toList(), more, more ? cursor(selected.getLast()) : null);
    }
    private Map<String, Object> baselineView(Map<String, Object> row) {
        var refs = Map.<String, Object>of("id", row.get("id"));
        var documentRefs = rows("SELECT document_id,document_version_id FROM baseline_document WHERE baseline_id=:id ORDER BY document_id", refs)
                .stream().map(r -> Map.of("document_id", r.get("document_id").toString(), "document_version_id", r.get("document_version_id").toString())).toList();
        var itemRefs = rows("SELECT design_item_id,design_item_version_id FROM baseline_item WHERE baseline_id=:id ORDER BY design_item_id", refs)
                .stream().map(r -> Map.of("design_item_id", r.get("design_item_id").toString(), "design_item_version_id", r.get("design_item_version_id").toString())).toList();
        return Map.of("id", row.get("id").toString(), "project_id", row.get("project_id").toString(), "version_no", row.get("version_no"),
                "documents", documentRefs, "items", itemRefs, "approved_by", row.get("published_by").toString(), "created_at", time(row.get("published_at")));
    }
}
