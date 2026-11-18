package com.beez.backend.status.service;

import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.DocumentKind;
import com.beez.backend.document.DocumentKindMapper;
import com.beez.backend.status.dto.DocumentStatusResponse;
import com.beez.backend.status.dto.ProposalStatusResponse;
import com.beez.backend.status.repository.StatusRepository;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MON-D01 문서 승인 현황, MON-C01 변경 제안 현황 (S15P31A108-104). 조회만 한다.
 * 문서 종류와 제안 상태는 건수가 0이어도 고정 순서로 모두 내려 화면이 빈칸 처리를 하지 않게 한다.
 */
@Service
@Transactional(readOnly = true)
public class StatusQueryService {

    /** 명세 StateCount 에 내려가는 제안 상태 순서 */
    static final List<String> PROPOSAL_STATUSES =
            List.of("PROPOSED", "IN_REVIEW", "APPROVED", "REJECTED", "PUBLISHED", "WITHDRAWN");

    /** 결정을 기다리는 상태 — 팀장 검토 필요 */
    static final List<String> REVIEW_REQUIRED = List.of("PROPOSED", "IN_REVIEW");

    private static final DocumentKindMapper KINDS = new DocumentKindMapper();

    private final ProjectAccessChecker access;
    private final StatusRepository repository;

    public StatusQueryService(ProjectAccessChecker access, StatusRepository repository) {
        this.access = access;
        this.repository = repository;
    }

    public DocumentStatusResponse documents(CallerContext caller, long projectId) {
        access.requireMember(caller, projectId);
        DocumentStatusResponse.Baseline baseline = repository.currentBaseline(projectId)
                .map(row -> new DocumentStatusResponse.Baseline(row.get("id").toString(),
                        ((Number) row.get("version_no")).intValue(),
                        ((Timestamp) row.get("published_at")).toInstant().toString()))
                .orElse(null);
        Map<DocumentKind, int[]> counts = new HashMap<>();
        for (Map<String, Object> row : repository.documentCounts(projectId)) {
            counts.put(KINDS.convertToEntityAttribute((String) row.get("type")),
                    new int[] {((Number) row.get("draft_count")).intValue(), ((Number) row.get("approved_count")).intValue()});
        }
        List<DocumentStatusResponse.KindCount> byKind = new ArrayList<>();
        for (DocumentKind kind : DocumentKind.values()) {
            int[] count = counts.getOrDefault(kind, new int[] {0, 0});
            byKind.add(new DocumentStatusResponse.KindCount(kind.name(), count[0], count[1]));
        }
        return new DocumentStatusResponse(baseline, byKind);
    }

    public ProposalStatusResponse proposals(CallerContext caller, long projectId) {
        access.requireMember(caller, projectId);
        Map<String, Integer> counts = new HashMap<>();
        for (Map<String, Object> row : repository.proposalCounts(projectId)) {
            counts.put((String) row.get("status"), ((Number) row.get("cnt")).intValue());
        }
        List<ProposalStatusResponse.StateCount> byStatus = new ArrayList<>();
        int reviewRequired = 0;
        for (String status : PROPOSAL_STATUSES) {
            int count = counts.getOrDefault(status, 0);
            byStatus.add(new ProposalStatusResponse.StateCount(status, count));
            if (REVIEW_REQUIRED.contains(status)) {
                reviewRequired += count;
            }
        }
        return new ProposalStatusResponse(byStatus, reviewRequired);
    }
}
