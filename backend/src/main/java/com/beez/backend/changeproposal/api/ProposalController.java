package com.beez.backend.changeproposal.api;

import com.beez.backend.changeproposal.dto.ProposalDtos.*;
import com.beez.backend.changeproposal.service.ProposalCommandService;
import com.beez.backend.changeproposal.service.ProposalQueryService;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.api.DocumentRequestOriginVerifier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/change-proposals")
@Tag(name = "Change proposals", description = "변경 제안 제출·반려 후 재제출·리비전과 전후 비교")
public class ProposalController {
    private final ProposalCommandService commands;
    private final ProposalQueryService queries;
    private final DocumentRequestOriginVerifier origins;
    public ProposalController(ProposalCommandService commands, ProposalQueryService queries, DocumentRequestOriginVerifier origins) {
        this.commands=commands; this.queries=queries; this.origins=origins;
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "CHG-A01 변경 제안 제출")
    public Proposal create(CallerContext caller, HttpServletRequest request, @PathVariable long projectId,
                           @RequestBody Input input, @RequestHeader("Idempotency-Key") String key) {
        origins.requireTrustedOrigin(request);
        return commands.create(caller, projectId, input, key);
    }
    @PutMapping("/{proposalId}")
    @Operation(summary = "CHG-A04 반려된 변경 제안 보완·재제출")
    public Proposal resubmit(CallerContext caller, HttpServletRequest request, @PathVariable long projectId,
                             @PathVariable long proposalId, @RequestBody Input input, @RequestHeader("Idempotency-Key") String key) {
        origins.requireTrustedOrigin(request);
        return commands.resubmit(caller, projectId, proposalId, input, key);
    }
    @GetMapping
    @Operation(summary = "CHG-A02 변경 제안 목록")
    public Page list(CallerContext caller, @PathVariable long projectId, @RequestParam(required = false) String status,
                     @RequestParam(required = false) String group,
                     @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        return queries.list(caller, projectId, status, group, cursor, limit);
    }
    @PostMapping("/{proposalId}/withdraw")
    @Operation(summary = "CHG-A15 제출한 변경 제안 거두기")
    public WithdrawResponse withdraw(CallerContext caller, HttpServletRequest request, @PathVariable long projectId,
                             @PathVariable long proposalId, @RequestBody WithdrawRequest input,
                             @RequestHeader("Idempotency-Key") String key) {
        origins.requireTrustedOrigin(request);
        return new WithdrawResponse(commands.withdraw(caller, projectId, proposalId, input, key));
    }
    @GetMapping("/{proposalId}")
    @Operation(summary = "CHG-A03 변경 제안 상세·비교")
    public Detail detail(CallerContext caller, @PathVariable long projectId, @PathVariable long proposalId,
                         @RequestParam(required = false) Integer revision) {
        return queries.detail(caller, projectId, proposalId, revision);
    }
    @GetMapping("/{proposalId}/revisions")
    @Operation(summary = "CHG-A03 제출 리비전·결정 이력 목록")
    public RevisionPage revisions(CallerContext caller, @PathVariable long projectId, @PathVariable long proposalId,
                                  @RequestParam(name = "before_revision", required = false) Integer before,
                                  @RequestParam(defaultValue = "20") int limit) {
        return queries.revisions(caller, projectId, proposalId, before, limit);
    }
}
