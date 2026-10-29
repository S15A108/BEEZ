package com.beez.backend.task.repository;

import com.beez.backend.task.entity.Task;
import com.beez.backend.task.entity.TaskStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * 목록 조회는 조건이 선택적(status·assignee·cursor)이라 Specification 으로 조립한다 (TaskSpecifications 참고).
 * JpaSpecificationExecutor 는 Spring Data 가 제공하는 "조건을 코드로 조립해 조회"하는 기능이다.
 */
public interface TaskRepository extends JpaRepository<Task, Long>, JpaSpecificationExecutor<Task> {

    /** 프로젝트 소속까지 같이 확인한다. 다른 프로젝트의 작업 번호로 접근하면 비어 있는 결과 → 404 */
    Optional<Task> findByIdAndProjectId(Long id, Long projectId);

    List<Task> findByProjectIdAndIdIn(Long projectId, List<Long> ids);

    /** 이 멤버가 담당한 작업 중 statuses 에 든 것 (팀원 제외·나가기의 열린 작업 인계, TaskHandoverService). */
    List<Task> findByProjectIdAndAssigneeMemberIdAndStatusIn(Long projectId, Long assigneeMemberId,
                                                             Collection<TaskStatus> statuses);
}
