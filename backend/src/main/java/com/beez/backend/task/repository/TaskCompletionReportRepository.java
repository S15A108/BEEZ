package com.beez.backend.task.repository;

import com.beez.backend.task.entity.TaskCompletionReport;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskCompletionReportRepository extends JpaRepository<TaskCompletionReport, Long> {

    List<TaskCompletionReport> findByTaskIdOrderByIdDesc(Long taskId);

    /** (작업, 계획 리비전) 에 delta 있는 완료 보고가 있는가 — S3 재전송 멱등·S4 완료 조건·발행 뒤 DELTA 재접수가 모두 이걸로 본다 */
    boolean existsByTaskIdAndPlanRevisionIdAndHasDeltaTrue(Long taskId, Long planRevisionId);

    /** (작업, 계획 리비전) 의 가장 최근 delta 완료 보고. DELTA 검증 요청에 delta 내용을 실을 때 */
    Optional<TaskCompletionReport> findFirstByTaskIdAndPlanRevisionIdAndHasDeltaTrueOrderByIdDesc(Long taskId, Long planRevisionId);
}
