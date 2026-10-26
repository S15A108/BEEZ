package com.beez.backend.task.repository;

import com.beez.backend.task.entity.TaskCompletionReport;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskCompletionReportRepository extends JpaRepository<TaskCompletionReport, Long> {

    List<TaskCompletionReport> findByTaskIdOrderByIdDesc(Long taskId);
}
