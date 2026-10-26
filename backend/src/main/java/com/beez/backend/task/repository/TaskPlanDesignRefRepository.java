package com.beez.backend.task.repository;

import com.beez.backend.task.entity.TaskPlanDesignRef;
import com.beez.backend.task.entity.TaskPlanDesignRefId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskPlanDesignRefRepository extends JpaRepository<TaskPlanDesignRef, TaskPlanDesignRefId> {

    List<TaskPlanDesignRef> findByPlanRevisionId(Long planRevisionId);

    List<TaskPlanDesignRef> findByPlanRevisionIdIn(List<Long> planRevisionIds);
}
