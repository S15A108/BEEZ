package com.beez.backend.planning.api;

import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.api.DocumentRequestOriginVerifier;
import com.beez.backend.planning.dto.AnswerRequest;
import com.beez.backend.planning.service.PlanningQuestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/planning-questions")
@Tag(name = "Planning questions", description = "기획 보완 질문 목록·답변 — 답변은 설계 항목을 바꾸지 않는다")
public class PlanningQuestionController {
    private final PlanningQuestionService questions;
    private final DocumentRequestOriginVerifier origins;

    public PlanningQuestionController(PlanningQuestionService questions, DocumentRequestOriginVerifier origins) {
        this.questions = questions; this.origins = origins;
    }

    @GetMapping
    @Operation(summary = "PLN-A03 기획 보완 질문 목록")
    public Map<String, Object> list(CallerContext caller, @PathVariable long projectId,
            @RequestParam(name = "document_version_id", required = false) Long documentVersionId,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        return questions.list(caller, projectId, documentVersionId, cursor, limit);
    }

    @PatchMapping("/{questionId}")
    @Operation(summary = "PLN-A04 기획 보완 질문 답변")
    public Map<String, Object> answer(CallerContext caller, HttpServletRequest request, @PathVariable long projectId,
            @PathVariable long questionId, @RequestBody AnswerRequest body) {
        origins.requireTrustedOrigin(request);
        return questions.answer(caller, projectId, questionId, body);
    }
}
