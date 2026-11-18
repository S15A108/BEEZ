package com.beez.backend.bee.api;

import com.beez.backend.bee.service.AssignmentSuggestionService;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.api.DocumentRequestOriginVerifier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** BEE-A02: 추천을 조회하며 작업의 담당자를 변경하지 않는다. */
@RestController
@Tag(name = "bee", description = "비비 추천·피드백·현재 상태")
public class AssignmentSuggestionController {
    private final AssignmentSuggestionService service;
    private final DocumentRequestOriginVerifier origins;

    public AssignmentSuggestionController(AssignmentSuggestionService service, DocumentRequestOriginVerifier origins) {
        this.service = service;
        this.origins = origins;
    }

    public record Request(List<String> taskIds) { }

    @PostMapping("/api/projects/{projectId}/assignment-suggestions")
    @Operation(summary = "작업 담당 후보 추천 (BEE-A02). 실제 담당자는 변경하지 않음")
    public Map<String, Object> suggest(CallerContext caller, HttpServletRequest request,
                                       @PathVariable("projectId") Long projectId,
                                       @RequestBody(required = false) Request body) {
        origins.requireTrustedOrigin(request);
        return service.suggest(caller, projectId, body == null ? null : body.taskIds());
    }
}
