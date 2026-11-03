package com.beez.backend.usage.api;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.usage.dto.UsageResponses.UsageSummary;
import com.beez.backend.usage.service.UsageQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 프로젝트 AI 사용량·추정 비용 (S15P31A108-169, MON-A02) */
@RestController
@Tag(name = "monitoring", description = "대시보드 조회 (MON)")
@RequestMapping("/api/projects/{id}")
public class UsageController {

    private final UsageQueryService usage;

    public UsageController(UsageQueryService usage) {
        this.usage = usage;
    }

    @Operation(summary = "MON-A02 프로젝트 AI 사용량·추정 비용")
    @GetMapping("/usage")
    public UsageSummary usage(CallerContext caller, @PathVariable("id") Long projectId,
                              @RequestParam(value = "from", required = false) String from,
                              @RequestParam(value = "to", required = false) String to,
                              @RequestParam(value = "task_id", required = false) String taskId,
                              @RequestParam(value = "validation_id", required = false) String validationId,
                              @RequestParam(value = "feature", required = false) String feature) {
        return usage.summary(caller, projectId, time(from, "from"), time(to, "to"), Ids.parse(taskId, "task_id"),
                Ids.parse(validationId, "validation_id"), feature);
    }

    /** 명세 Time: 오프셋이 있는 ISO-8601 (예: 2026-10-23T03:00:00Z, 2026-10-23T12:00:00+09:00). 없으면 null(서비스가 400) */
    private static Instant time(String raw, String field) {
        if (raw == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", field + " 은 시각(ISO-8601, 오프셋 포함)이어야 합니다");
        }
    }
}
