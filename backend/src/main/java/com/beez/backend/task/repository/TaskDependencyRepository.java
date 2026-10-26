package com.beez.backend.task.repository;

import com.beez.backend.task.entity.TaskDependency;
import com.beez.backend.task.entity.TaskDependencyId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskDependencyRepository extends JpaRepository<TaskDependency, TaskDependencyId> {

    List<TaskDependency> findByTaskId(Long taskId);

    /** 이 작업을 선행으로 삼는 쪽 (역방향). 1단계 순환 검사에 쓴다 */
    List<TaskDependency> findByDependsOnTaskId(Long dependsOnTaskId);

    @Modifying
    @Query("delete from TaskDependency d where d.taskId = :taskId")
    void deleteAllByTaskId(@Param("taskId") Long taskId);
}
