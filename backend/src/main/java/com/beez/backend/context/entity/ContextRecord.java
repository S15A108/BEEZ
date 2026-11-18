package com.beez.backend.context.entity;

import com.beez.backend.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 공유 작업 기록 한 판 (ERD context_record, MCP-05~07, CTX-A02~A04). 저장 후 고치지 않는다 (V2 트리거).
 * 수정은 previous_id 로 이전 판을 잇는 새 행이다. 첫 판은 root_id·previous_id 가 비어 있고 revision_no=1 (DDL CHECK).
 * embedding 칸은 의미 검색(박상은)이 붙을 때 쓴다 — 지금은 매핑하지 않아 비어 있다.
 */
@Entity
@Table(name = "context_record")
public class ContextRecord extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(name = "root_id", updatable = false)
    private Long rootId;

    @Column(name = "previous_id", updatable = false)
    private Long previousId;

    @Column(name = "revision_no", nullable = false, updatable = false)
    private Integer revisionNo;

    @Column(name = "task_id", updatable = false)
    private Long taskId;

    @Column(name = "plan_revision_id", updatable = false)
    private Long planRevisionId;

    @Column(name = "source_packet_id", updatable = false)
    private Long sourcePacketId;

    @Column(name = "author_account_id", nullable = false, updatable = false)
    private Long authorAccountId;

    @Column(name = "author_token_id", updatable = false)
    private Long authorTokenId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false)
    private ContextRecordKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, updatable = false)
    private Visibility visibility;

    @Column(name = "summary", nullable = false, updatable = false)
    private String summary;

    @Column(name = "detail", updatable = false)
    private String detail;

    protected ContextRecord() {
    }

    /** 첫 판 */
    public static ContextRecord first(Long projectId, Long taskId, Long planRevisionId, Long sourcePacketId,
                                      Long authorAccountId, Long authorTokenId, ContextRecordKind kind,
                                      Visibility visibility, String summary, String detail) {
        ContextRecord r = new ContextRecord();
        r.projectId = projectId;
        r.revisionNo = 1;
        r.taskId = taskId;
        r.planRevisionId = planRevisionId;
        r.sourcePacketId = sourcePacketId;
        r.authorAccountId = authorAccountId;
        r.authorTokenId = authorTokenId;
        r.kind = kind;
        r.visibility = visibility;
        r.summary = summary;
        r.detail = detail;
        return r;
    }

    /** previous 를 잇는 다음 판. 첫 판 번호(root)는 이전 판의 root, 이전 판이 첫 판이면 그 번호 */
    public static ContextRecord next(ContextRecord previous, Long taskId, Long planRevisionId, Long sourcePacketId,
                                     Long authorAccountId, Long authorTokenId, ContextRecordKind kind,
                                     Visibility visibility, String summary, String detail) {
        ContextRecord r = first(previous.projectId, taskId, planRevisionId, sourcePacketId, authorAccountId,
                authorTokenId, kind, visibility, summary, detail);
        r.previousId = previous.id;
        r.revisionNo = previous.revisionNo + 1;
        if (previous.rootId == null) {
            r.rootId = previous.id;
        } else {
            r.rootId = previous.rootId;
        }
        return r;
    }

    /** API 의 root_id 는 필수라, 첫 판은 자기 번호를 돌려준다 (DB 에는 비어 있음) */
    public Long rootIdForApi() {
        if (rootId == null) {
            return id;
        }
        return rootId;
    }

    public boolean isVisibleTo(Long accountId) {
        return visibility == Visibility.SHARED || authorAccountId.equals(accountId);
    }

    public Long getId() { return id; }
    public Long getProjectId() { return projectId; }
    public Long getRootId() { return rootId; }
    public Long getPreviousId() { return previousId; }
    public Integer getRevisionNo() { return revisionNo; }
    public Long getTaskId() { return taskId; }
    public Long getPlanRevisionId() { return planRevisionId; }
    public Long getSourcePacketId() { return sourcePacketId; }
    public Long getAuthorAccountId() { return authorAccountId; }
    public Long getAuthorTokenId() { return authorTokenId; }
    public ContextRecordKind getKind() { return kind; }
    public Visibility getVisibility() { return visibility; }
    public String getSummary() { return summary; }
    public String getDetail() { return detail; }
}
