package com.beez.backend.task.repository;

import com.beez.backend.task.entity.TaskPlanRevision;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskPlanRevisionRepository extends JpaRepository<TaskPlanRevision, Long> {

    Optional<TaskPlanRevision> findByIdAndTaskId(Long id, Long taskId);

    /** 현재 최신 리비전 번호. 리비전이 없으면 0 */
    @Query("select coalesce(max(r.revisionNo), 0) from TaskPlanRevision r where r.taskId = :taskId")
    int findMaxRevisionNo(@Param("taskId") Long taskId);

    /** 첫 페이지 (최신 순). Pageable 로 limit+1 을 준다 */
    List<TaskPlanRevision> findByTaskIdOrderByIdDesc(Long taskId, Pageable pageable);

    /** 다음 페이지: cursor 보다 작은 id 만 */
    List<TaskPlanRevision> findByTaskIdAndIdLessThanOrderByIdDesc(Long taskId, Long cursor, Pageable pageable);

    List<TaskPlanRevision> findByIdIn(List<Long> ids);
}
