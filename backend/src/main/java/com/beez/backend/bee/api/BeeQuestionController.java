package com.beez.backend.bee.api;

import com.beez.backend.bee.service.BeeQuestionService;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.api.DocumentRequestOriginVerifier;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** BEE-A01: question text and the answer are not persisted. */
@RestController
public class BeeQuestionController {
    private final BeeQuestionService service;
    private final DocumentRequestOriginVerifier origins;

    public BeeQuestionController(BeeQuestionService service, DocumentRequestOriginVerifier origins) {
        this.service = service; this.origins = origins;
    }

    public record Request(String question, String taskId, String scope) { }

    @PostMapping("/api/projects/{projectId}/bee/questions")
    @Operation(summary = "BEE-A01 승인 문서와 설계 기준으로 질문")
    public Map<String, Object> ask(CallerContext caller, HttpServletRequest request,
                                   @PathVariable("projectId") Long projectId,
                                   @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                   @RequestBody(required = false) Request body) {
        origins.requireTrustedOrigin(request);
        Long task = body == null || body.taskId() == null ? null : Ids.parse(body.taskId(), "task_id");
        return service.answer(caller, projectId, body == null ? null : body.question(), task,
                body == null ? null : body.scope(), key);
    }
}
