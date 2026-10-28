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
import com.beez.backend.context.dto.VersionRefDto;
import com.beez.backend.context.dto.WriteContextArgs;
import com.beez.backend.context.entity.ContextRecordKind;
import com.beez.backend.context.entity.Visibility;
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
 * MCP-01(업무 저장)·05·06·07 업무 서비스 (S15P31A108-166). 서비스 직접 호출.
 * 패킷 내용 구성(박상은 -131·-132)은 아직 없어 구성 결과를 테스트가 만들어 넘긴다.
 */
class ContextMcpServiceIntegrationTests extends IntegrationTestSupport {

    @Autowired
    DataSource dataSource;

    @Autowired
    ContextMcpService mcp;

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
    void savePacket_storesImmutablePacketWithRefs_andLogsAccess() {
        String v = String.valueOf(api.currentVersionId("API-01"));
        ContextPacketResponse packet = mcp.saveDevelopmentContext(memberAgent, projectId, packetArgs(List.of(v)));

        assertThat(packet.designItems()).hasSize(1);
        assertThat(packet.designItems().get(0).key()).isEqualTo("API-01");
        assertThat(packet.constraints()).containsExactly("본문 토큰 유지");
        assertThat(packet.contentHash()).hasSize(64);
        assertThat(count("select count(*) from dev_context_packet_ref")).isEqualTo(1);
        assertThat(count("select count(*) from context_access_log where tool = 'get_development_context' and target_kind = 'PACKET'"))
                .isEqualTo(1);

        ReadContextResult read = mcp.readContext(memberAgent, projectId, "PACKET", packet.id());
        assertThat(read.packet().contentHash()).isEqualTo(packet.contentHash());
        assertThat(read.record()).isNull();
    }

    @Test
    void savePacket_rules() {
        String v = String.valueOf(api.currentVersionId("API-01"));
        // 웹(사람 세션)은 패킷을 만들지 않는다 (CTX-A01)
        assertDenied(() -> mcp.saveDevelopmentContext(CallerContext.human(member), projectId, packetArgs(List.of(v))),
                HttpStatus.FORBIDDEN, "forbidden");
        // 현재 기준 설계에 없는 버전
        String unpublished = String.valueOf(api.newVersion("API-01", 2));
        assertDenied(() -> mcp.saveDevelopmentContext(memberAgent, projectId, packetArgs(List.of(unpublished))),
                HttpStatus.BAD_REQUEST, "validation_failed");
        // project_id 인수가 토큰 프로젝트와 다름
        assertDenied(() -> mcp.saveDevelopmentContext(memberAgent, projectId, new SavePacketArgs("x", String.valueOf(projectId + 99),
                null, List.of(), List.of(), List.of(), List.of(), List.of())), HttpStatus.NOT_FOUND, "not_found");
        // 기준 설계가 없는 프로젝트
        api.jdbc.sql("update project set current_baseline_id = null where id = :p").param("p", projectId).update();
        assertDenied(() -> mcp.saveDevelopmentContext(memberAgent, projectId, packetArgs(List.of())),
                HttpStatus.NOT_FOUND, "baseline_not_published");
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
        assertThat(count("select count(*) from context_access_log where target_kind = 'CONTEXT_RECORD'")).isEqualTo(1);
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
        api.publish(2, Map.of("API-01", api.newVersion("API-01", 2), "API-02", api.currentVersionId("API-02")));
        assertThat(page2.items().get(0).referencesCurrent()).isTrue();
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

        // 서비스를 거치지 않아도 V7 묶음 FK 가 막는다
        assertThatThrownBy(() -> api.jdbc.sql("""
                        insert into context_record (project_id, revision_no, source_packet_id, author_account_id, kind, visibility, summary)
                        values (:p, 1, :k, :a, 'PROGRESS', 'SHARED', 's')
                        """).param("p", projectId).param("k", foreignPacket).param("a", member).update())
                .hasMessageContaining("fk_context_record_packet");
    }
}
