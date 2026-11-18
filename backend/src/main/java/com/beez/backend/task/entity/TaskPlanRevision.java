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
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 계획 리비전. 테이블 {@code task_plan_revision}. <b>불변</b> — 저장 뒤 바꾸지 않는다 (DB 트리거도 UPDATE 를 막는다).
 * 계획을 고치면 revision_no 를 올려 새 행을 만든다.
 *
 * <p>JSONB 컬럼은 Hibernate 6 의 {@code @JdbcTypeCode(SqlTypes.JSON)} 으로 List/Map 에 바로 매핑한다. 추가 라이브러리 없음.
 */
@Entity
@Table(name = "task_plan_revision")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskPlanRevision extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false, updatable = false)
    private Long taskId;

    @Column(name = "revision_no", nullable = false, updatable = false)
    private Integer revisionNo;

    /** 리비전 당시 제목 스냅샷. 현재 제목은 task.title */
    @Column(name = "title", nullable = false, updatable = false)
    private String title;

    @Column(name = "goal", nullable = false, updatable = false)
    private String goal;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "included_scope", nullable = false, updatable = false)
    private List<String> includedScope;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "excluded_scope", nullable = false, updatable = false)
    private List<String> excludedScope;

    @Column(name = "approach", updatable = false)
    private String approach;

    /** 리비전 당시 선행 작업 스냅샷. 현재 관계는 task_dependency */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dependency_task_ids", nullable = false, updatable = false)
    private List<Long> dependencyTaskIds;

    /** 설계 항목에 연결되지 않은 새 내용. AI 분석 대상 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "unlinked", nullable = false, updatable = false)
    private List<Map<String, Object>> unlinked;

    @Column(name = "no_contract_change", nullable = false, updatable = false)
    private boolean noContractChange;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "completion_criteria", nullable = false, updatable = false)
    private List<String> completionCriteria;

    /** 계획 내용 + 참조 버전을 정렬해 만든 SHA-256. 검증 결과 재사용 키 */
    @Column(name = "input_hash", nullable = false, updatable = false)
    private String inputHash;

    /** 작성 계정. 에이전트 작성이면 토큰 소유 계정 */
    @Column(name = "created_by_account_id", nullable = false, updatable = false)
    private Long createdByAccountId;

    @Column(name = "created_by_token_id", updatable = false)
    private Long createdByTokenId;

    /** 근거 패킷. NULL = 근거 패킷 없음 */
    @Column(name = "context_packet_id", updatable = false)
    private Long contextPacketId;

    /**
     * 이 계획을 저장할 때의 현재 기준 설계 (V8, -166 리뷰 반영). NULL = 그때 발행된 기준이 없었음.
     * 계획 저장은 프로젝트 행을 잠근 채 읽으므로 발행과 겹쳐도 정확하다. "계획 이후 변경"(report_task_state)의 기준점.
     * 시각 비교로 추정하지 않는 이유: 리비전 시각(앱)과 발행 시각(DB)의 시계가 어긋나면 변경이 빠진다.
     */
    @Column(name = "base_baseline_id", updatable = false)
    private Long baseBaselineId;

    @Builder
    private TaskPlanRevision(Long taskId, Integer revisionNo, String title, String goal,
                             List<String> includedScope, List<String> excludedScope, String approach,
                             List<Long> dependencyTaskIds, List<Map<String, Object>> unlinked,
                             boolean noContractChange, List<String> completionCriteria, String inputHash,
                             Long createdByAccountId, Long createdByTokenId, Long contextPacketId,
                             Long baseBaselineId) {
        this.taskId = taskId;
        this.revisionNo = revisionNo;
        this.title = title;
        this.goal = goal;
        this.includedScope = includedScope;
        this.excludedScope = excludedScope;
        this.approach = approach;
        this.dependencyTaskIds = dependencyTaskIds;
        this.unlinked = unlinked;
        this.noContractChange = noContractChange;
        this.completionCriteria = completionCriteria;
        this.inputHash = inputHash;
        this.createdByAccountId = createdByAccountId;
        this.createdByTokenId = createdByTokenId;
        this.contextPacketId = contextPacketId;
        this.baseBaselineId = baseBaselineId;
    }
}
