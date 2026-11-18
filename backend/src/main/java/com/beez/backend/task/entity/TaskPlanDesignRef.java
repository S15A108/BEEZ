package com.beez.backend.task.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 계획이 참조하는 설계 항목 한 줄. 테이블 {@code task_plan_design_ref}. 불변.
 * 이 테이블에는 created_at/updated_at 이 없으므로 BaseTimeEntity 를 상속하지 않는다.
 *
 * <p>설계 항목·버전은 번호로만 가리킨다. (버전, 항목) 쌍이 맞는지는 DB 복합 FK 가 확인한다.
 */
@Entity
@Table(name = "task_plan_design_ref")
@IdClass(TaskPlanDesignRefId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskPlanDesignRef {

    @Id
    @Column(name = "plan_revision_id", nullable = false)
    private Long planRevisionId;

    @Id
    @Column(name = "design_item_id", nullable = false)
    private Long designItemId;

    /** 계획이 기준으로 삼은 버전 = 계획 작성 당시 현재 baseline 의 버전 */
    @Column(name = "design_item_version_id", nullable = false)
    private Long designItemVersionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "relation", nullable = false)
    private PlanRelation relation;

    /** CHANGES 일 때만. 변경 후 구조화 내용 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposed_content")
    private Map<String, Object> proposedContent;

    public TaskPlanDesignRef(Long planRevisionId, Long designItemId, Long designItemVersionId,
                             PlanRelation relation, Map<String, Object> proposedContent) {
        this.planRevisionId = planRevisionId;
        this.designItemId = designItemId;
        this.designItemVersionId = designItemVersionId;
        this.relation = relation;
        this.proposedContent = proposedContent;
    }
}
