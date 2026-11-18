package com.beez.backend.context.entity;

import com.beez.backend.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * get_development_context 결과 패킷 (ERD dev_context_packet, MCP-01, TRC-03 "당시 패킷"). 저장 후 고치지 않는다 (V2 트리거).
 * 내용 구성(검색·의존 설계·JSON)은 박상은(-131·-132), 저장은 이 서비스. content 에는 구성 결과를 그대로 담는다.
 */
@Entity
@Table(name = "dev_context_packet")
public class DevContextPacket extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(name = "task_id", updatable = false)
    private Long taskId;

    @Column(name = "agent_token_id", updatable = false)
    private Long agentTokenId;

    @Column(name = "request", nullable = false, updatable = false)
    private String request;

    @Column(name = "baseline_id", nullable = false, updatable = false)
    private Long baselineId;

    /** {constraints, unresolved, related_tasks, sources, design_item_version_ids} */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content", nullable = false, updatable = false)
    private Map<String, Object> content;

    @Column(name = "content_hash", nullable = false, updatable = false)
    private String contentHash;

    protected DevContextPacket() {
    }

    public static DevContextPacket create(Long projectId, Long taskId, Long agentTokenId, String request, Long baselineId,
                                          Map<String, Object> content, String contentHash) {
        DevContextPacket p = new DevContextPacket();
        p.projectId = projectId;
        p.taskId = taskId;
        p.agentTokenId = agentTokenId;
        p.request = request;
        p.baselineId = baselineId;
        p.content = content;
        p.contentHash = contentHash;
        return p;
    }

    public Long getId() { return id; }
    public Long getProjectId() { return projectId; }
    public Long getTaskId() { return taskId; }
    public Long getAgentTokenId() { return agentTokenId; }
    public String getRequest() { return request; }
    public Long getBaselineId() { return baselineId; }
    public Map<String, Object> getContent() { return content; }
    public String getContentHash() { return contentHash; }
}
