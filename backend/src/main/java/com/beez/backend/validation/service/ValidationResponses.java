package com.beez.backend.validation.service;

import com.beez.backend.common.api.Ids;
import com.beez.backend.validation.dto.FindingResponse;
import com.beez.backend.validation.dto.RelatedTaskRefResponse;
import com.beez.backend.validation.dto.ValidationDesignRefResponse;
import com.beez.backend.validation.dto.ValidationResponse;
import com.beez.backend.validation.repository.FindingRow;
import com.beez.backend.validation.repository.ValidationRepository;
import com.beez.backend.validation.repository.ValidationRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 검증 행 → 명세 Validation 응답. 참조·발견 항목을 읽어 붙이고 ERROR 면 error_code 를 요약에서 꺼낸다 */
@Component
public class ValidationResponses {

    /** validation.summary 안에서 ERROR 분류 코드를 두는 키 (공통 "검증 실행 오류 규약"의 Validation.error_code) */
    public static final String SUMMARY_ERROR_CODE = "error_code";

    private final ValidationRepository repository;
    private final ObjectMapper json;

    public ValidationResponses(ValidationRepository repository, ObjectMapper json) {
        this.repository = repository;
        this.json = json;
    }

    public ValidationResponse toResponse(ValidationRow v) {
        List<ValidationDesignRefResponse> designRefs = new ArrayList<>();
        for (Map<String, Object> r : repository.designRefs(v.id())) {
            designRefs.add(new ValidationDesignRefResponse(Ids.str(id(r.get("design_item_id"))),
                    Ids.str(id(r.get("design_item_version_id"))), (String) r.get("relation")));
        }
        List<RelatedTaskRefResponse> taskRefs = new ArrayList<>();
        for (Map<String, Object> r : repository.taskRefs(v.id())) {
            taskRefs.add(new RelatedTaskRefResponse(Ids.str(id(r.get("related_task_id"))),
                    Ids.str(id(r.get("related_plan_revision_id")))));
        }
        List<FindingResponse> findings = new ArrayList<>();
        for (FindingRow f : repository.findings(v.id())) {
            findings.add(new FindingResponse(Ids.str(f.id()), Ids.str(f.validationRunId()), f.source(), f.kind(), f.severity(), f.message(),
                    Ids.str(f.designItemId()), Ids.str(f.designItemVersionId()), Ids.str(f.relatedTaskId()), f.sources(),
                    f.resolution(), f.nextAction()));
        }
        String errorCode = null;
        if ("ERROR".equals(v.status())) {
            errorCode = errorCode(v.summary());
        }
        return new ValidationResponse(Ids.str(v.id()), Ids.str(v.taskId()), Ids.str(v.planRevisionId()),
                Ids.str(v.baselineId()), v.status(), v.trigger(), v.staleReason(), v.currentAttempt(), designRefs, taskRefs,
                findings, false,
                v.requestedAt(), v.finishedAt(), errorCode);
    }

    /** 요약 JSON 을 Map 으로. 비어 있으면 빈 Map */
    @SuppressWarnings("unchecked")
    public Map<String, Object> summary(String summaryJson) {
        if (summaryJson == null || summaryJson.isBlank()) {
            return new java.util.LinkedHashMap<>();
        }
        return new java.util.LinkedHashMap<>(json.readValue(summaryJson, Map.class));
    }

    private String errorCode(String summaryJson) {
        Map<String, Object> summary = summary(summaryJson);
        if (summary.containsKey(SUMMARY_ERROR_CODE)) {
            return String.valueOf(summary.get(SUMMARY_ERROR_CODE));
        }
        // 분류를 남기지 못한 ERROR — 규약의 기본 분류
        return "validation_execution_failed";
    }

    private static Long id(Object value) {
        if (value == null) {
            return null;
        }
        return ((Number) value).longValue();
    }
}
