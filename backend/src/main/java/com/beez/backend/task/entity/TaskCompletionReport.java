package com.beez.backend.task.entity;

import com.beez.backend.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 완료 보고. 테이블 {@code task_completion_report}. 불변 (UPDATE 차단 트리거).
 * 계획 대비 달라진 점(delta)이 있으면 has_delta=true 이고 작업은 WAITING(REVALIDATION) 으로 간다.
 * DB CHECK: has_delta = (delta 배열 길이 > 0). 서비스가 같은 규칙으로 계산해 넣는다.
 */
@Entity
@Table(name = "task_completion_report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskCompletionReport extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false, updatable = false)
    private Long taskId;

    @Column(name = "plan_revision_id", nullable = false, updatable = false)
    private Long planRevisionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "delta", nullable = false, updatable = false)
    private List<Map<String, Object>> delta;

    @Column(name = "has_delta", nullable = false, updatable = false)
    private boolean hasDelta;

    @Column(name = "summary", updatable = false)
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "completion_checks", nullable = false, updatable = false)
    private List<Map<String, Object>> completionChecks;

    /** 델타 검증(trigger=DELTA). 검증 실행(S15P31A108-59)에서 채운다 */
    @Column(name = "delta_validation_id", updatable = false)
    private Long deltaValidationId;

    @Column(name = "reported_by_token_id", updatable = false)
    private Long reportedByTokenId;

    public TaskCompletionReport(Long taskId, Long planRevisionId, List<Map<String, Object>> delta, String summary,
                                List<Map<String, Object>> completionChecks, Long reportedByTokenId) {
        this.taskId = taskId;
        this.planRevisionId = planRevisionId;
        this.delta = delta;
        this.hasDelta = !delta.isEmpty();
        this.summary = summary;
        this.completionChecks = completionChecks;
        this.reportedByTokenId = reportedByTokenId;
    }
}
