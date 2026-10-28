package com.beez.backend.contextaccess.entity;

import com.beez.backend.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 누가 어떤 설계 버전·패킷·기록을 봤는지 한 줄 (ERD context_access_log, CTX-05·06, S15P31A108-165).
 * 저장 후 고치지 않는다 (V5 UPDATE 금지 트리거). 원문·요청 문장·토큰 값은 칸 자체가 없다 — 번호만 남긴다.
 *
 * <p>mcp_audit_log(-177, 전진)와 다르다: 그쪽은 "어느 도구를 누가 불러 결과가 뭐였나", 여기는 "어떤 버전의 내용을 봤나".
 */
@Entity
@Table(name = "context_access_log")
public class ContextAccessLog extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    /** 호출 계정. 에이전트면 토큰 소유 계정 (V5) */
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /** 에이전트 호출이면 토큰 번호, 사람 세션이면 null */
    @Column(name = "agent_token_id")
    private Long agentTokenId;

    @Column(name = "task_id")
    private Long taskId;

    /** 호출한 도구·API 이름. 예: get_design_changes, get_development_context, CTX-A01 */
    @Column(name = "tool", nullable = false)
    private String tool;

    /** 본 대상의 종류. 예: BASELINE, PACKET, CONTEXT_RECORD */
    @Column(name = "target_kind", nullable = false)
    private String targetKind;

    @Column(name = "target_id")
    private Long targetId;

    /** 그때 참조한 기준 설계 */
    @Column(name = "baseline_id")
    private Long baselineId;

    @Column(name = "packet_id")
    private Long packetId;

    @Column(name = "accessed_at", nullable = false)
    private Instant accessedAt;

    protected ContextAccessLog() {
    }

    public static ContextAccessLog of(Long projectId, Long accountId, Long agentTokenId, Long taskId,
                                      String tool, String targetKind, Long targetId, Long baselineId, Long packetId) {
        ContextAccessLog log = new ContextAccessLog();
        log.projectId = projectId;
        log.accountId = accountId;
        log.agentTokenId = agentTokenId;
        log.taskId = taskId;
        log.tool = tool;
        log.targetKind = targetKind;
        log.targetId = targetId;
        log.baselineId = baselineId;
        log.packetId = packetId;
        log.accessedAt = Instant.now();
        return log;
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public Long getAgentTokenId() {
        return agentTokenId;
    }

    public Long getTaskId() {
        return taskId;
    }

    public String getTool() {
        return tool;
    }

    public String getTargetKind() {
        return targetKind;
    }

    public Long getTargetId() {
        return targetId;
    }

    public Long getBaselineId() {
        return baselineId;
    }

    public Long getPacketId() {
        return packetId;
    }

    public Instant getAccessedAt() {
        return accessedAt;
    }
}
