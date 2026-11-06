package com.beez.backend.planning;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.project.ProjectRole;
import com.beez.backend.support.IntegrationTestSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S15P31A108-232 시험 바탕: 기준 설계가 아직 없는 프로젝트(팀장 1·멤버 1)와 분석이 끝난 문서 버전 하나, 그리고 133·134 계약 모양의
 * 가짜 AI 응답 도우미. AI 는 {@code AiClient} 대역이고 어댑터·서비스·저장소는 실제다.
 */
abstract class PlanningTestSupport extends IntegrationTestSupport {
    static final String ORIGIN = "http://localhost:5173";
    static final String TEXT_HASH = "f8e265adf8d8be5ee57309613f5bbe84035e3aa6af5fd98c7dc4bc4bc1be1dd8";
    static final String CONTENT = "# 기획서\n\n삭제 권한은 아직 정하지 않았다.\n\n로그인은 이메일로 한다.\n";

    @Autowired JdbcTemplate jdbc;
    long project, leader, member, document, version;
    CallerContext leaderCaller, memberCaller;

    @BeforeEach
    void planningSetup() {
        // DB 는 시험 클래스끼리 공유한다 — 다른 시험이 남긴 대기/완료 작업을 워커가 먼저 집지 않게 비운다.
        jdbc.update("DELETE FROM design_draft_candidate");
        jdbc.update("DELETE FROM design_draft_job");
        jdbc.update("DELETE FROM planning_question_run");
        jdbc.update("UPDATE document_processing SET status='FAILED',source_status='FAILED',index_status='FAILED',error_code='test_reset',lease_token=null,lease_until=null WHERE status IN ('PENDING','RUNNING','COMPLETED')");
        var p = fixtures().projectWithLeader();
        project = p.projectId(); leader = p.leaderAccountId();
        member = fixtures().account();
        fixtures().member(project, member, ProjectRole.MEMBER);
        leaderCaller = CallerContext.human(leader); memberCaller = CallerContext.human(member);
        document = jdbc.queryForObject("INSERT INTO document(project_id,type,title,created_by) VALUES(?,'PLAN','기획서',?) RETURNING id", Long.class, project, leader);
        version = documentVersion(document, 1, CONTENT);
    }

    /** 분석(색인)까지 끝난 문서 버전. */
    long documentVersion(long doc, int no, String text) {
        long v = jdbc.queryForObject("""
                INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash,content_text,created_by)
                VALUES(?,?,'DRAFT','fixture.md',?,?,?) RETURNING id
                """, Long.class, doc, no, "c".repeat(64), text, leader);
        jdbc.update("""
                INSERT INTO document_processing(document_version_id,attempt,status,source_status,index_status,text_hash)
                VALUES(?,1,'COMPLETED','READY','READY',?)
                """, v, TEXT_HASH);
        return v;
    }

    /** 설계 항목 DRAFT 하나. 출처는 {@link #version}. */
    long[] draftItem(String key, String kind, String description) {
        long item = jdbc.queryForObject("INSERT INTO design_item(project_id,key,kind) VALUES(?,?,?) RETURNING id", Long.class, project, key, kind);
        String content = "{\"description\":\"" + description + "\"}";
        long v = jdbc.queryForObject("""
                INSERT INTO design_item_version(design_item_id,version_no,status,title,schema_version,content,content_hash,origin,
                                                source_document_version_id,source_locator,created_by)
                VALUES(?,1,'DRAFT',?,1,cast(? AS jsonb),?,'HUMAN',?,cast('{"location":"L1-L2"}' AS jsonb),?) RETURNING id
                """, Long.class, item, description, content, com.beez.backend.designitem.support.DesignJson.hash(Map.of("description", description)), version, leader);
        return new long[] {item, v};
    }

    void publishBaseline() {
        long baseline = jdbc.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,1,?) RETURNING id", Long.class, project, leader);
        jdbc.update("UPDATE project SET current_baseline_id=? WHERE id=?", baseline, project);
    }

    // ---- 133 질문 생성 응답 ----

    Map<String, Object> usage(String feature) {
        return Map.of("request_key", "llm-" + UUID.randomUUID(), "feature", feature, "model", "test-model", "input_tokens", 100,
                "output_tokens", 50, "latency_ms", 10, "failed", false, "tokens_known", true);
    }

    Map<String, Object> evidence(String quote, int line) {
        return Map.of("document_id", "" + document, "document_version_id", "" + version, "version_no", 1, "block_id", "blk_" + line,
                "locator", Map.of("type", "text_lines", "start_line", line, "end_line", line), "location", "기획서 / L" + line, "quote", quote);
    }

    Map<String, Object> candidate(String key, String kind) {
        return Map.of("candidate_key", key, "kind", kind, "status", "REVIEW_CANDIDATE", "signal", "UNDECIDED_STATEMENT",
                "summary", key + " 요약", "evidence", List.of(evidence("삭제 권한은 아직 정하지 않았다.", 3)));
    }

    Map<String, Object> question(String candidateKey, int rank, String type, Map<String, Object> format) {
        var q = new LinkedHashMap<String, Object>();
        q.put("question_key", "q-" + candidateKey); q.put("candidate_key", candidateKey); q.put("rank", rank);
        q.put("question_type", type); q.put("text", candidateKey + " 질문입니다."); q.put("answer_format", format);
        q.put("origin", "MODEL"); q.put("basis", "REVIEW_CANDIDATE");
        return q;
    }

    /** 키 이름으로 질문 3종(서술·선택·단답)을 만든다. */
    Map<String, Object> questionsResponse(String... keys) {
        var formats = List.of(Map.<String, Object>of("type", "ESSAY", "max_length", 2000),
                Map.<String, Object>of("type", "CHOICE", "options", List.of("가", "나", "다"), "multiple", false, "allow_other", true),
                Map.<String, Object>of("type", "SHORT_ANSWER", "max_length", 200));
        var candidates = new ArrayList<Map<String, Object>>();
        var questions = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < keys.length; i++) {
            candidates.add(candidate(keys[i], "MISSING_CANDIDATE"));
            var format = formats.get(i % 3);
            questions.add(question(keys[i], i + 1, (String) format.get("type"), format));
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("document", Map.of("document_id", "" + document, "document_version_id", "" + version, "version_no", 1));
        result.put("kind", "PLAN"); result.put("text_hash", TEXT_HASH); result.put("parser_profile", "markdown-v1");
        result.put("max_questions", 20); result.put("candidates", candidates); result.put("questions", questions);
        result.put("truncated_candidate_keys", List.of()); result.put("omitted_candidates", 0);
        return Map.of("result", result, "origin", "MODEL", "model", "test-model", "usage", List.of(usage("DOCUMENT_PARSE")));
    }

    void fails(HttpStatus status, String code, ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCode()).isEqualTo(code);
        });
    }

    int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
}
