package com.beez.backend.relation.dto;

import java.util.List;

/** MON-A03 응답 */
public record RelationGraphResponse(List<RelationNodeResponse> nodes, List<RelationEdgeResponse> edges) {
}
