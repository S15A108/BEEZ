package com.beez.backend.designitem.api;

import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.designitem.dto.CreateDesignItemRequest;
import com.beez.backend.designitem.dto.DesignItemPageResponse;
import com.beez.backend.designitem.dto.DesignItemResponse;
import com.beez.backend.designitem.dto.UpdateDesignItemRequest;
import com.beez.backend.designitem.service.DesignItemCommandService;
import com.beez.backend.designitem.service.DesignItemQueryService;
import com.beez.backend.document.api.DocumentRequestOriginVerifier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Design Items", description = "수동 초안 검토와 최초 기준 설계 발행")
public class DesignItemController {
    private final DesignItemQueryService queries;
    private final DesignItemCommandService commands;
    private final DocumentRequestOriginVerifier origins;

    public DesignItemController(DesignItemQueryService queries, DesignItemCommandService commands,
                                 DocumentRequestOriginVerifier origins) {
        this.queries = queries;
        this.commands = commands;
        this.origins = origins;
    }

    @GetMapping("/api/projects/{projectId}/design-items")
    @Operation(summary = "PLN-A06 설계 항목 조회")
    public DesignItemPageResponse list(CallerContext caller, @PathVariable long projectId,
                                    @RequestParam(defaultValue = "APPROVED") String status,
                                    @RequestParam(required = false) String kind,
                                    @RequestParam(name = "document_version_id", required = false) Long source,
                                    @RequestParam(required = false) String cursor,
                                    @RequestParam(defaultValue = "20") int limit) {
        return queries.list(caller, projectId, status, kind, source, cursor, limit);
    }

    @PostMapping("/api/projects/{projectId}/design-items")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "PLN-A07 수동 설계 항목 추가")
    public DesignItemResponse create(CallerContext caller, HttpServletRequest request, @PathVariable long projectId,
                                      @Valid @RequestBody CreateDesignItemRequest input,
                                      @RequestHeader("Idempotency-Key") String key) {
        origins.requireTrustedOrigin(request);
        return commands.create(caller, projectId, input, key);
    }

    @PatchMapping("/api/human/design-items/{id}")
    @Operation(summary = "PLN-A08 수동 초안 수정")
    public DesignItemResponse update(CallerContext caller, HttpServletRequest request, @PathVariable long id,
                                      @Valid @RequestBody UpdateDesignItemRequest input) {
        origins.requireTrustedOrigin(request);
        return commands.update(caller, id, input);
    }

    @DeleteMapping("/api/human/design-items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "PLN-A09 초안 발행 제외")
    public void exclude(CallerContext caller, HttpServletRequest request, @PathVariable long id,
                        @RequestParam("project_id") long project, @RequestParam("expected_version_id") long version,
                        @RequestParam("expected_content_hash") String hash) {
        origins.requireTrustedOrigin(request);
        commands.exclude(caller, project, id, version, hash);
    }
}
