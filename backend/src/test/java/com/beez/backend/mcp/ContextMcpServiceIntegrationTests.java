package com.beez.backend.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.PageResponse;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.context.dto.ContextPacketResponse;
import com.beez.backend.context.dto.ContextRecordResponse;
import com.beez.backend.context.dto.ContextRecordSummaryResponse;
import com.beez.backend.context.dto.FindContextArgs;
import com.beez.backend.context.dto.ReadContextResult;
import com.beez.backend.context.dto.SavePacketArgs;
import com.beez.backend.context.dto.SourceDto;
import com.beez.backend.context.dto.RelatedTaskDto;
import com.beez.backend.context.dto.VersionRefDto;
import com.beez.backend.context.dto.WriteContextArgs;
import com.beez.backend.context.entity.ContextRecordKind;
import com.beez.backend.context.entity.Visibility;
import com.beez.backend.context.service.ContextPacketService;
import com.beez.backend.mcp.dto.GetDevelopmentContextArgs;
import com.beez.backend.mcp.service.ContextMcpService;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * MCP-01·05·06·07 업무 서비스 (S15P31A108-166). 서비스 직접 호출.
 * 패킷 내용 구성(박상은 -131·-132)은 아직 없어, 도구 입구는 503 으로 아무것도 저장하지 않는지 보고
 * 구성 결과가 있을 때의 저장 규칙은 패킷 저장 서비스에 구성 결과를 직접 넣어 확인한다.
 */
class ContextMcpServiceIntegrationTests extends IntegrationTestSupport {

    @Autowired
    DataSource dataSource;

    @Autowired
    ContextMcpService mcp;

    @Autowired
    ContextPacketService packets;

    TaskApiSupport api;
    Long projectId;
    Long member;
    Long leader;
    CallerContext memberAgent;
    CallerContext leaderAgent;

    @BeforeEach
    void setUp() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        projectId = api.projectId();
        member = api.accountId("member@test");
        leader = api.accountId("leader@test");
        memberAgent = CallerContext.agent(member, api.agentTokenId());
        leaderAgent = CallerContext.agent(leader, api.leaderAgentTokenId());
    }

    private static void assertDenied(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCode()).isEqualTo(code);
        });
    }

    private Integer count(String sql) {
        return api.jdbc.sql(sql).query(Integer.class).single();
    }

    // ---- MCP-01 패킷 저장 ----

    private SavePacketArgs packetArgs(List<String> versionIds) {
        return new SavePacketArgs("로그인 화면 구현", null, null, versionIds, List.of("본문 토큰 유지"), List.of("만료 정책 미정"),
                List.of(), List.of());
    }

    @Test
    void getDevelopmentContext_withoutCompositionModule_is503_andSavesNothing() {
        // 구성 모듈(박상은) 연결 전: 빈 패킷을 "근거 없음"처럼 주지 않고 503, 저장도 하지 않는다
        assertDenied(() -> mcp.getDevelopmentContext(memberAgent, projectId,
                new GetDevelopmentContextArgs("로그인 화면 구현", String.valueOf(projectId), null)),
                HttpStatus.SERVICE_UNAVAILABLE, "context_not_ready");
        assertThat(count("select count(*) from dev_context_packet")).isZero();
    }

    @Test
    void getDevelopmentContext_checksCallerAndArgsFirst() {
        // 웹(사람 세션)은 패킷을 만들지 않는다 (CTX-A01)
        assertDenied(() -> mcp.getDevelopmentContext(CallerContext.human(member), projectId,
                new GetDevelopmentContextArgs("x", null, null)), HttpStatus.FORBIDDEN, "forbidden");
        // 요청 문장 필수
        assertDenied(() -> mcp.getDevelopmentContext(memberAgent, projectId,
                new GetDevelopmentContextArgs(" ", null, null)), HttpStatus.BAD_REQUEST, "validation_failed");
        // project_id 인수가 토큰 프로젝트와 다름 → 404 (503 보다 먼저)
        assertDenied(() -> mcp.getDevelopmentContext(memberAgent, projectId,
                new GetDevelopmentContextArgs("x", String.valueOf(projectId + 99), null)), HttpStatus.NOT_FOUND, "not_found");
        // task_id 형식 오류 400, 없는·다른 프로젝트 작업 404
        assertDenied(() -> mcp.getDevelopmentContext(memberAgent, projectId,
                new GetDevelopmentContextArgs("x", null, "abc")), HttpStatus.BAD_REQUEST, "validation_failed");
        assertDenied(() -> mcp.getDevelopmentContext(memberAgent, projectId,
                new GetDevelopmentContextArgs("x", null, "999999")), HttpStatus.NOT_FOUND, "not_found");
        // 프로젝트 멤버가 아님
        Long outsider = api.accountId("outsider@test");
        assertDenied(() -> mcp.getDevelopmentContext(CallerContext.human(outsider), projectId,
                new GetDevelopmentContextArgs("x", null, null)), HttpStatus.NOT_FOUND, "not_found");
    }

    @Test
    void savePacket_rules() {
        // 구성 결과가 생긴 뒤의 저장 규칙은 패킷 저장 서비스에서 확인한다
        // project_id 인수가 토큰 프로젝트와 다름
        assertDenied(() -> packets.save(memberAgent, projectId, new SavePacketArgs("x", String.valueOf(projectId + 99),
                null, List.of(), List.of(), List.of(), List.of(), List.of())), HttpStatus.NOT_FOUND, "not_found");
        // 기준 설계가 없는 프로젝트
        api.jdbc.sql("update project set current_baseline_id = null where id = :p").param("p", projectId).update();
        assertDenied(() -> packets.save(memberAgent, projectId, packetArgs(List.of())),
                HttpStatus.NOT_FOUND, "baseline_not_published");
    }

    @Test
    void savePacket_withComposition_storesImmutablePacketWithRefs() {
        String v = String.valueOf(api.currentVersionId("API-01"));
        ContextPacketResponse packet = packets.save(memberAgent, projectId, packetArgs(List.of(v)));

        assertThat(packet.designItems()).hasSize(1);
        assertThat(packet.designItems().get(0).key()).isEqualTo("API-01");
        assertThat(packet.constraints()).containsExactly("본문 토큰 유지");
        assertThat(packet.contentHash()).hasSize(64);
        assertThat(count("select count(*) from dev_context_packet_ref")).isEqualTo(1);

        ReadContextResult read = mcp.readContext(memberAgent, projectId, "PACKET", packet.id());
        assertThat(read.packet().contentHash()).isEqualTo(packet.contentHash());
        assertThat(read.record()).isNull();
    }

    @Test
    void savedPacket_keepsDesignStateAtSaveTime_evenAfterVersionIsSuperseded() {
        Long v = api.currentVersionId("API-01");
        ContextPacketResponse saved = packets.save(memberAgent, projectId, packetArgs(List.of(String.valueOf(v))));
        assertThat(saved.designItems().get(0).status()).isEqualTo("APPROVED");

        // 나중에 그 버전이 SUPERSEDED 가 되어도
        api.jdbc.sql("update design_item_version set status = 'SUPERSEDED' where id = :v").param("v", v).update();

        ReadContextResult read = mcp.readContext(memberAgent, projectId, "PACKET", saved.id());
        assertThat(read.packet().designItems().get(0).status()).isEqualTo("APPROVED");
        assertThat(read.packet().designItems()).isEqualTo(saved.designItems());
        assertThat(read.packet().contentHash()).isEqualTo(saved.contentHash());
        assertThat(read.packet()).isEqualTo(saved);   // 저장 응답과 나중 조회 응답이 모든 값에서 같다 (시각 정밀도 포함)
    }

    @Test
    void packetHash_keepsListAndStringBoundaries() {
        SavePacketArgs joined = new SavePacketArgs("같은 요청", null, null, List.of(), List.of("a, b"), List.of(), List.of(), List.of());
        SavePacketArgs split = new SavePacketArgs("같은 요청", null, null, List.of(), List.of("a", "b"), List.of(), List.of(), List.of());
        String h1 = packets.save(memberAgent, projectId, joined).contentHash();
        String h2 = packets.save(memberAgent, projectId, split).contentHash();
        assertThat(h1).hasSize(64).isNotEqualTo(h2);
    }

    @Test
    void savePacket_waitsForConcurrentPublish_andSeesTheNewBaseline() throws Exception {
        Long v1 = api.currentVersionId("API-01");
        Long api02v1 = api.currentVersionId("API-02");
        Long leader = api.accountId("leader@test");
        // 새 기준 v2 를 미리 만들어 두고(아직 현재 기준 아님), 발행 트랜잭션에서 현재 기준만 바꾼다
        Long api01v2 = api.newVersion("API-01", 2);
        api.jdbc.sql("insert into baseline (project_id, version_no, published_by) values (:p, 2, :a)")
                .param("p", projectId).param("a", leader).update();
        Long v2 = api.jdbc.sql("select id from baseline where project_id = :p and version_no = 2").param("p", projectId)
                .query(Long.class).single();
        for (Map.Entry<String, Long> e : Map.of("API-01", api01v2, "API-02", api02v1).entrySet()) {
            api.jdbc.sql("insert into baseline_item (baseline_id, project_id, design_item_id, design_item_version_id) values (:b, :p, :i, :v)")
                    .param("b", v2).param("p", projectId).param("i", api.designItemId(e.getKey())).param("v", e.getValue()).update();
        }

        try (java.sql.Connection publish = dataSource.getConnection()) {
            publish.setAutoCommit(false);
            // 발행처럼 프로젝트 행을 FOR UPDATE 로 잡는다
            try (java.sql.PreparedStatement st = publish.prepareStatement("select id from project where id = ? for update")) {
                st.setLong(1, projectId);
                st.executeQuery();
            }
            // 발행 도중에 v1 으로 패킷 저장을 시도
            java.util.concurrent.CompletableFuture<ContextPacketResponse> save = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> packets.save(memberAgent, projectId, packetArgs(List.of(String.valueOf(v1)))));
            Thread.sleep(500);
            assertThat(save).isNotDone();   // 발행이 끝날 때까지 기다린다

            try (java.sql.PreparedStatement st = publish.prepareStatement(
                    "update design_item_version set status = 'SUPERSEDED' where id = ?")) {
                st.setLong(1, v1);
                st.executeUpdate();
            }
            try (java.sql.PreparedStatement st = publish.prepareStatement("update project set current_baseline_id = ? where id = ?")) {
                st.setLong(1, v2);
                st.setLong(2, projectId);
                st.executeUpdate();
            }
            publish.commit();

            // 발행 뒤의 기준(v2)으로 판단한다: v1 은 이제 현재 기준에 없으므로 400. 옛 기준 ID + SUPERSEDED 패킷이 생기지 않는다
            assertThatThrownBy(() -> save.get(10, java.util.concurrent.TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ApiException.class)
                    .satisfies(e -> assertThat(((ApiException) e.getCause()).getCode()).isEqualTo("validation_failed"));
        }
        assertThat(count("select count(*) from dev_context_packet")).isZero();
    }

    @Test
    void savePacket_relatedTasksAndSourcesMustBelongToProject() {
        // 없는 작업 → 404
        SavePacketArgs foreignTask = new SavePacketArgs("x", null, null, List.of(), List.of(), List.of(),
                List.of(new RelatedTaskDto("999999", "1", "READY")), List.of());
        assertDenied(() -> packets.save(memberAgent, projectId, foreignTask), HttpStatus.NOT_FOUND, "not_found");
        // 명세에 없는 상태 → 400
        Long taskId = api.jdbc.sql("insert into task (project_id, key, title, status) values (:p, 'T-R', 'r', 'DRAFT') returning id")
                .param("p", projectId).query(Long.class).single();
        SavePacketArgs badStatus = new SavePacketArgs("x", null, null, List.of(), List.of(), List.of(),
                List.of(new RelatedTaskDto(String.valueOf(taskId), "1", "FOO")), List.of());
        assertDenied(() -> packets.save(memberAgent, projectId, badStatus), HttpStatus.BAD_REQUEST, "validation_failed");
        // 이 프로젝트에 없는 문서 버전 → 400
        SavePacketArgs foreignSource = new SavePacketArgs("x", null, null, List.of(), List.of(), List.of(), List.of(),
                List.of(new SourceDto("999999", "999999", 1, "1장")));
        assertDenied(() -> packets.save(memberAgent, projectId, foreignSource), HttpStatus.BAD_REQUEST, "validation_failed");
        assertThat(count("select count(*) from dev_context_packet")).isZero();
    }

    private SavePacketArgs withRelated(Long taskId, Long planRevisionId, String status) {
        return new SavePacketArgs("x", null, null, List.of(), List.of(), List.of(),
                List.of(new RelatedTaskDto(String.valueOf(taskId), String.valueOf(planRevisionId), status)), List.of());
    }

    private SavePacketArgs withSource(Long documentId, Long documentVersionId, int versionNo) {
        return new SavePacketArgs("x", null, null, List.of(), List.of(), List.of(), List.of(),
                List.of(new SourceDto(String.valueOf(documentId), String.valueOf(documentVersionId), versionNo, "1장")));
    }

    private Long currentPlanRevisionId(Long taskId) {
        return api.jdbc.sql("select current_plan_revision_id from task where id = :t").param("t", taskId)
                .query(Long.class).single();
    }

    @Test
    void savePacket_relatedTasksMustMatchTheTasksCurrentPlanAndStatus() throws Exception {
        String tasks = "/api/projects/" + projectId + "/tasks";
        var created = api.post(tasks, api.planJson("로그인 API", api.readsRef("API-01"), "[]"), member);
        assertThat(created.statusCode()).isEqualTo(201);
        Long taskId = api.jdbc.sql("select id from task where project_id = :p and key = 'T-1'").param("p", projectId)
                .query(Long.class).single();
        Long revision1 = currentPlanRevisionId(taskId);

        // 작업의 지금 계획·상태 그대로 → 저장된다
        assertThat(packets.save(memberAgent, projectId, withRelated(taskId, revision1, "READY")).relatedTasks()).hasSize(1);
        // 상태가 작업의 지금 상태와 다르면 → 400
        assertDenied(() -> packets.save(memberAgent, projectId, withRelated(taskId, revision1, "IN_PROGRESS")),
                HttpStatus.BAD_REQUEST, "validation_failed");

        // 계획 리비전 2 를 올린 뒤, 지난 리비전 1 을 관련 작업으로 넘기면 → 400
        String plan = api.planJson("로그인 API v2", api.readsRef("API-02"), "[]").trim();
        String body = plan.substring(0, plan.lastIndexOf('}'))
                + ",\n  \"expected_revision_no\": 1,\n  \"expected_lock_version\": "
                + com.beez.backend.task.TaskApiSupport.longField(created.body(), "lock_version") + "\n}";
        assertThat(api.post(tasks + "/" + taskId + "/plan-revisions", body, member).statusCode()).isEqualTo(201);
        Long revision2 = currentPlanRevisionId(taskId);
        assertThat(revision2).isNotEqualTo(revision1);
        // 상태는 지금 값으로 맞추고 리비전만 지난 것을 넘긴다
        String statusNow = api.jdbc.sql("select status from task where id = :t").param("t", taskId).query(String.class).single();
        assertDenied(() -> packets.save(memberAgent, projectId, withRelated(taskId, revision1, statusNow)),
                HttpStatus.BAD_REQUEST, "validation_failed");
        assertThat(packets.save(memberAgent, projectId, withRelated(taskId, revision2, statusNow)).relatedTasks()).hasSize(1);
        assertThat(count("select count(*) from dev_context_packet")).isEqualTo(2);
    }

    /** 문서 버전 하나. approved 면 APPROVED(승인 시각 있음), 아니면 DRAFT */
    private Long documentVersion(Long documentId, int versionNo, boolean approved) {
        String sql = "insert into document_version (document_id, version_no, status, storage_key, content_hash) "
                + "values (:d, :n, 'DRAFT', :k, :h) returning id";
        if (approved) {
            sql = "insert into document_version (document_id, version_no, status, storage_key, content_hash, approved_at) "
                    + "values (:d, :n, 'APPROVED', :k, :h, now()) returning id";
        }
        return api.jdbc.sql(sql).param("d", documentId).param("n", versionNo)
                .param("k", "doc/" + documentId + "/v" + versionNo).param("h", "h-" + documentId + "-" + versionNo)
                .query(Long.class).single();
    }

    /** 기준 설계에 문서의 승인 버전을 넣는다 (발행이 baseline_document 에 적는 것과 같다) */
    private void putDocumentInBaseline(Long baselineId, Long documentId, Long documentVersionId) {
        api.jdbc.sql("""
                        insert into baseline_document (baseline_id, project_id, document_id, document_version_id)
                        values (:b, :p, :d, :v)
                        """)
                .param("b", baselineId).param("p", projectId).param("d", documentId).param("v", documentVersionId).update();
    }

    @Test
    void savePacket_sourcesMustBeDocumentVersionsOfTheCurrentBaseline() {
        Long doc = api.jdbc.sql("insert into document (project_id, type, title, created_by) values (:p, 'API_SPEC', 'API 명세', :a) returning id")
                .param("p", projectId).param("a", leader).query(Long.class).single();
        Long approved1 = documentVersion(doc, 1, true);
        Long draft2 = documentVersion(doc, 2, false);
        Long baseline1 = api.jdbc.sql("select current_baseline_id from project where id = :p").param("p", projectId)
                .query(Long.class).single();
        putDocumentInBaseline(baseline1, doc, approved1);

        // 현재 기준에 들어 있는 승인 버전 → 저장된다
        assertThat(packets.save(memberAgent, projectId, withSource(doc, approved1, 1)).sources()).hasSize(1);
        // 같은 프로젝트의 미승인 버전 → 400
        assertDenied(() -> packets.save(memberAgent, projectId, withSource(doc, draft2, 2)),
                HttpStatus.BAD_REQUEST, "validation_failed");
        // 버전 번호가 실제 번호와 다름 → 400
        assertDenied(() -> packets.save(memberAgent, projectId, withSource(doc, approved1, 2)),
                HttpStatus.BAD_REQUEST, "validation_failed");

        // 새 기준 v2 에서 문서 버전 3 이 승인됨 → 버전 1 은 지난 기준의 버전
        Long api01 = api.currentVersionId("API-01");
        Long api02 = api.currentVersionId("API-02");
        Long approved3 = documentVersion(doc, 3, true);
        Long baseline2 = api.publish(2, Map.of("API-01", api01, "API-02", api02));
        putDocumentInBaseline(baseline2, doc, approved3);
        assertDenied(() -> packets.save(memberAgent, projectId, withSource(doc, approved1, 1)),
                HttpStatus.BAD_REQUEST, "validation_failed");
        assertThat(packets.save(memberAgent, projectId, withSource(doc, approved3, 3)).sources()).hasSize(1);
        assertThat(count("select count(*) from dev_context_packet")).isEqualTo(2);
    }

    @Test
    void savePacket_versionsMustBeInCurrentBaseline() {
        String unpublished = String.valueOf(api.newVersion("API-01", 2));
        assertDenied(() -> packets.save(memberAgent, projectId, packetArgs(List.of(unpublished))),
                HttpStatus.BAD_REQUEST, "validation_failed");
    }

    // ---- MCP-07·06·05 공유 기록 ----

    private WriteContextArgs record(String previousId, Visibility visibility, String summary) {
        VersionRefDto ref = new VersionRefDto(String.valueOf(api.designItemId("API-01")),
                String.valueOf(api.currentVersionId("API-01")));
        return new WriteContextArgs(previousId, null, null, null, ContextRecordKind.DECISION, visibility, summary, "상세",
                List.of(ref), "k-" + summary);
    }

    @Test
    void write_firstAndNextRevision_rootAndLatestFlags() {
        ContextRecordResponse first = mcp.writeContext(memberAgent, projectId, record(null, Visibility.SHARED, "토큰 결정"));
        assertThat(first.revisionNo()).isEqualTo(1);
        assertThat(first.rootId()).isEqualTo(first.id());
        assertThat(first.isLatestRevision()).isTrue();
        assertThat(first.referencesCurrent()).isTrue();

        ContextRecordResponse second = mcp.writeContext(memberAgent, projectId, record(first.id(), Visibility.SHARED, "토큰 결정 수정"));
        assertThat(second.revisionNo()).isEqualTo(2);
        assertThat(second.rootId()).isEqualTo(first.id());
        assertThat(second.previousId()).isEqualTo(first.id());

        // 같은 이전 판을 다시 고치면 409
        assertDenied(() -> mcp.writeContext(memberAgent, projectId, record(first.id(), Visibility.SHARED, "또 수정")),
                HttpStatus.CONFLICT, "revision_conflict");

        ReadContextResult old = mcp.readContext(memberAgent, projectId, "RECORD", first.id());
        assertThat(old.record().isLatestRevision()).isFalse();
        assertThat(old.record().createdAt()).isEqualTo(first.createdAt());   // 저장 응답과 조회의 시각이 같다
        assertThat(count("select count(*) from context_access_log where target_kind = 'CONTEXT_RECORD'")).isEqualTo(1);
    }

    @Test
    void write_sameKeyRetry_returnsFirstResult_andDifferentInputIs409() {
        WriteContextArgs args = record(null, Visibility.SHARED, "재시도");
        ContextRecordResponse first = mcp.writeContext(memberAgent, projectId, args);
        // 응답을 잃고 같은 키·같은 입력으로 다시 보냄 → 처음 결과, 기록은 하나
        ContextRecordResponse again = mcp.writeContext(memberAgent, projectId, args);
        assertThat(again).isEqualTo(first);   // 저장했다 되읽은 결과가 모든 칸에서 처음과 같다
        assertThat(count("select count(*) from context_record")).isEqualTo(1);

        // 같은 키에 다른 입력 → 409
        WriteContextArgs changed = new WriteContextArgs(null, null, null, null, ContextRecordKind.ISSUE, Visibility.SHARED,
                "재시도", "상세", List.of(), "k-재시도");
        assertDenied(() -> mcp.writeContext(memberAgent, projectId, changed), HttpStatus.CONFLICT, "idempotency_conflict");
        // 다른 사람은 같은 키를 써도 따로 처리된다 (키 범위 = 계정·프로젝트·동작)
        ContextRecordResponse leaders = mcp.writeContext(leaderAgent, projectId, args);
        assertThat(leaders.id()).isNotEqualTo(first.id());
    }

    @Test
    void write_nullInsideDesignRefs_is400() {
        List<VersionRefDto> refs = new java.util.ArrayList<>();
        refs.add(null);
        WriteContextArgs args = new WriteContextArgs(null, null, null, null, ContextRecordKind.DECISION, Visibility.SHARED,
                "널 참조", "상세", refs, "k-null");
        assertDenied(() -> mcp.writeContext(memberAgent, projectId, args), HttpStatus.BAD_REQUEST, "validation_failed");
    }

    @Test
    void privateSuccessorOfSharedRecord_isNotRevealedToOthers() {
        ContextRecordResponse shared = mcp.writeContext(leaderAgent, projectId, record(null, Visibility.SHARED, "공유 뒤 비공개"));
        mcp.writeContext(leaderAgent, projectId, record(shared.id(), Visibility.PRIVATE, "비공개로 고침"));

        // 다른 사람에게는 공유 판이 여전히 최신으로 보인다 (볼 수 없는 판이 있다는 사실을 드러내지 않음)
        assertThat(mcp.readContext(memberAgent, projectId, "RECORD", shared.id()).record().isLatestRevision()).isTrue();
        assertThat(mcp.findContext(memberAgent, projectId, new FindContextArgs("공유 뒤", null, null, null, null)).items())
                .allMatch(ContextRecordSummaryResponse::isLatestRevision);
        // 작성자 본인에게는 최신이 아니다
        assertThat(mcp.readContext(leaderAgent, projectId, "RECORD", shared.id()).record().isLatestRevision()).isFalse();
    }

    @Test
    void othersRecords_sharedIs403OnEdit_privateLooksMissing() {
        ContextRecordResponse shared = mcp.writeContext(leaderAgent, projectId, record(null, Visibility.SHARED, "팀장 공유"));
        ContextRecordResponse hidden = mcp.writeContext(leaderAgent, projectId, record(null, Visibility.PRIVATE, "팀장 비공개"));

        assertDenied(() -> mcp.writeContext(memberAgent, projectId, record(shared.id(), Visibility.SHARED, "남의 것 수정")),
                HttpStatus.FORBIDDEN, "forbidden");
        assertDenied(() -> mcp.writeContext(memberAgent, projectId, record(hidden.id(), Visibility.SHARED, "비공개 수정")),
                HttpStatus.NOT_FOUND, "not_found");
        assertDenied(() -> mcp.readContext(memberAgent, projectId, "RECORD", hidden.id()), HttpStatus.NOT_FOUND, "not_found");

        // 검색: 남의 비공개는 건수에서도 빠진다
        PageResponse<ContextRecordSummaryResponse> found = mcp.findContext(memberAgent, projectId,
                new FindContextArgs("팀장", null, null, null, null));
        assertThat(found.items()).extracting(ContextRecordSummaryResponse::id).containsExactly(shared.id());
        // 작성자 본인은 보인다
        assertThat(mcp.findContext(leaderAgent, projectId, new FindContextArgs("팀장", null, null, null, null)).items()).hasSize(2);
    }

    @Test
    void find_filtersBeforePaging_foreignTaskIsEmpty_andCurrentnessFollowsBaseline() {
        for (int i = 0; i < 3; i++) {
            mcp.writeContext(memberAgent, projectId, record(null, Visibility.SHARED, "로그인 " + i));
        }
        PageResponse<ContextRecordSummaryResponse> page1 = mcp.findContext(memberAgent, projectId,
                new FindContextArgs("로그인", null, List.of("DECISION"), null, 2));
        assertThat(page1.items()).hasSize(2);
        assertThat(page1.hasMore()).isTrue();
        PageResponse<ContextRecordSummaryResponse> page2 = mcp.findContext(memberAgent, projectId,
                new FindContextArgs("로그인", null, List.of("DECISION"), page1.nextCursor(), 2));
        assertThat(page2.items()).hasSize(1);
        assertThat(mcp.findContext(memberAgent, projectId, new FindContextArgs("로그인", null, List.of("ISSUE"), null, null)).items())
                .isEmpty();

        api.jdbc.sql("insert into project (name, owner_account_id) values ('Q', :a)").param("a", leader).update();
        Long other = api.jdbc.sql("select id from project where name = 'Q'").query(Long.class).single();
        api.jdbc.sql("insert into task (project_id, key, title, status) values (:p, 'T-1', 'q', 'DRAFT')").param("p", other).update();
        Long otherTask = api.jdbc.sql("select id from task where project_id = :p").param("p", other).query(Long.class).single();
        assertThat(mcp.findContext(memberAgent, projectId, new FindContextArgs("로그인", String.valueOf(otherTask), null, null, null))
                .items()).isEmpty();

        // API-01 이 바뀌면 그 버전을 참조한 기록은 현재가 아니다
        assertThat(page2.items().get(0).referencesCurrent()).isTrue();   // 발행 전에는 현재
        api.publish(2, Map.of("API-01", api.newVersion("API-01", 2), "API-02", api.currentVersionId("API-02")));
        assertThat(mcp.findContext(memberAgent, projectId, new FindContextArgs("로그인", null, null, null, null)).items())
                .allMatch(s -> !s.referencesCurrent());
    }

    @Test
    void write_rejectsForeignPacket_andDatabaseAlsoBlocksIt() {
        // 다른 프로젝트 Q 와 그 패킷
        api.jdbc.sql("insert into project (name, owner_account_id) values ('Q', :a)").param("a", leader).update();
        Long other = api.jdbc.sql("select id from project where name = 'Q'").query(Long.class).single();
        api.jdbc.sql("insert into baseline (project_id, version_no, published_by) values (:p, 1, :a)").param("p", other).param("a", leader).update();
        Long otherBaseline = api.jdbc.sql("select id from baseline where project_id = :p").param("p", other).query(Long.class).single();
        api.jdbc.sql("insert into dev_context_packet (project_id, request, baseline_id, content, content_hash) values (:p, 'x', :b, '{}', 'h')")
                .param("p", other).param("b", otherBaseline).update();
        Long foreignPacket = api.jdbc.sql("select max(id) from dev_context_packet").query(Long.class).single();

        WriteContextArgs args = new WriteContextArgs(null, null, null, String.valueOf(foreignPacket), ContextRecordKind.PROGRESS,
                Visibility.SHARED, "진행", "상세", List.of(), "k");
        assertDenied(() -> mcp.writeContext(memberAgent, projectId, args), HttpStatus.NOT_FOUND, "not_found");

        // 서비스를 거치지 않아도 V8 묶음 FK 가 막는다
        assertThatThrownBy(() -> api.jdbc.sql("""
                        insert into context_record (project_id, revision_no, source_packet_id, author_account_id, kind, visibility, summary)
                        values (:p, 1, :k, :a, 'PROGRESS', 'SHARED', 's')
                        """).param("p", projectId).param("k", foreignPacket).param("a", member).update())
                .hasMessageContaining("fk_context_record_packet");
    }
}
