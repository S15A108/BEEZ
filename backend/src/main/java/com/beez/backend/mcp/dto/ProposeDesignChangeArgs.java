package com.beez.backend.mcp.dto;

import com.beez.backend.changeproposal.dto.ProposalDtos.DocumentChange;
import com.beez.backend.changeproposal.dto.ProposalDtos.Input;
import com.beez.backend.changeproposal.dto.ProposalDtos.Item;
import java.util.List;

/**
 * MCP-04 propose_design_change 도구 인수 (02-mcp-tools MCP-04). REST CHG-A01 요청({@link Input})에 idempotency_key 가 더해진 모양.
 * REST 는 키를 헤더로 받아 {@link Input} 에 칸이 없으므로 따로 둔다. 재제출용 칸(expected_revision·expected_snapshot_hash)은
 * 신규 생성 도구라 받지 않는다 — 재제출·거두기 도구는 등록하지 않는다(02-mcp-tools MCP-04).
 */
public record ProposeDesignChangeArgs(
        String baseBaselineId,
        String title,
        String reason,
        String taskId,
        String validationId,
        List<Item> items,
        List<DocumentChange> documents,
        String previousProposalId,
        String idempotencyKey
) {

    public Input toInput() {
        return new Input(baseBaselineId, title, reason, taskId, validationId, items, documents, null, null, previousProposalId);
    }
}
