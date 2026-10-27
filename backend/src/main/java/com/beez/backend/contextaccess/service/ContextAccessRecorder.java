package com.beez.backend.contextaccess.service;

import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.contextaccess.entity.ContextAccessLog;
import com.beez.backend.contextaccess.repository.ContextAccessLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 컨텍스트 조회 이력 기록 (CTX-05·06, S15P31A108-165).
 *
 * <p><b>호출 규칙.</b> 설계 내용을 돌려주는 조회의 서비스 안에서, 권한 검사를 통과한 뒤, 결과를 돌려주기 전에 한 번 부른다.
 * 조회와 <b>같은 트랜잭션</b>에서 저장한다(Propagation.MANDATORY — 트랜잭션 밖에서 부르면 예외). 그래서 "조회 성공 = 이력 1건"이고,
 * 이력 저장이 실패하면 조회도 실패한다. 조회 서비스는 {@code @Transactional(readOnly = true)} 를 쓸 수 없다.
 *
 * <p><b>기록 지점.</b> get_design_changes(MCP-03, -164), get_development_context 패킷 저장(MCP-01), read_context(MCP-06),
 * REST CTX-A01 패킷 조회, CTX-A03 기록 상세. 검색 목록(MCP-05, CTX-A02)은 감사 기록(mcp_audit_log, -177)이 덮으므로 여기서는 남기지 않는다.
 *
 * <p><b>남기지 않는 것.</b> 요청 문장, 기록 원문, 토큰 값. 인자에 문자열 내용이 없고 번호만 받는다 (MCP 규약 "토큰·비밀번호·개인 대화 원문은 기록하지 않는다").
 *
 * <p><b>소속 확인.</b> 작업 번호는 여기서 "이 프로젝트 것인지" 확인하고 아니면 비워서 저장한다 — 호출 지점마다 확인을 반복하지 않는다.
 * 기준·토큰·작업은 V5 복합 FK((id, project_id))가 DB 에서도 막는다.
 */
@Service
public class ContextAccessRecorder {

    public static final String KIND_BASELINE = "BASELINE";
    public static final String KIND_PACKET = "PACKET";
    public static final String KIND_CONTEXT_RECORD = "CONTEXT_RECORD";

    private static final Logger log = LoggerFactory.getLogger(ContextAccessRecorder.class);

    private final ContextAccessLogRepository logs;
    private final JdbcClient jdbc;

    public ContextAccessRecorder(ContextAccessLogRepository logs, JdbcClient jdbc) {
        this.logs = logs;
        this.jdbc = jdbc;
    }

    /**
     * @param actor      권한 검사를 통과한 호출자 (계정·토큰 번호를 여기서 꺼낸다)
     * @param taskId     관련 작업. 이 프로젝트 작업이 아니면(다른 프로젝트·없는 번호) 비워서 저장한다
     * @param tool       도구·API 이름 (예: "get_design_changes")
     * @param targetKind {@link #KIND_BASELINE} 등
     * @param targetId   본 대상 번호
     * @param baselineId 그때 참조한 기준 설계
     * @param packetId   패킷을 봤거나 만들었으면 그 번호
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ContextAccessLog record(ProjectActor actor, Long taskId, String tool, String targetKind, Long targetId,
                                   Long baselineId, Long packetId) {
        Long taskInProject = null;
        if (taskId != null) {
            if (taskBelongsTo(actor.projectId(), taskId)) {
                taskInProject = taskId;
            } else {
                // 호출자 쪽에서 걸렀어야 할 번호. 섞어 저장하지 않고 비운다 (FK 도 막는다)
                log.warn("context access: task {} is not in project {}, recorded without task", taskId, actor.projectId());
            }
        }
        ContextAccessLog row = ContextAccessLog.of(actor.projectId(), actor.accountId(), actor.agentTokenId(), taskInProject,
                tool, targetKind, targetId, baselineId, packetId);
        return logs.save(row);
    }

    private boolean taskBelongsTo(Long projectId, Long taskId) {
        Integer count = jdbc.sql("select count(*) from task where id = :t and project_id = :p")
                .param("t", taskId).param("p", projectId).query(Integer.class).single();
        return count > 0;
    }
}
