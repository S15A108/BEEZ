package com.beez.backend.relation.service;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.api.PageResponse;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.relation.dto.DependencyCandidateResponse;
import com.beez.backend.relation.dto.DependencyHistoryResponse;
import com.beez.backend.relation.dto.DesignDependenciesResponse;
import com.beez.backend.relation.dto.DesignDependencyResponse;
import com.beez.backend.relation.repository.DependencyRow;
import com.beez.backend.relation.repository.HistoryRow;
import com.beez.backend.relation.repository.RelationRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TRC-A01 설계 항목 의존관계 조회, TRC-A02 의존관계 이력 조회 (S15P31A108-167).
 * 조회만 한다 — 단순 조회로 관계를 확정하지 않는다 (TRC-A01 처리 규칙).
 */
@Service
public class DesignDependencyQueryService {

    /** 명세 DesignDependency.relation. MVP 의 설계 항목 간 관계 종류는 하나다 */
    static final String DEPENDS_ON = "DEPENDS_ON";

    private final ProjectAccessChecker access;
    private final RelationRepository relations;

    public DesignDependencyQueryService(ProjectAccessChecker access, RelationRepository relations) {
        this.access = access;
        this.relations = relations;
    }

    /**
     * 항목이 한쪽 끝인 관계. items = 확정·재확인 필요 (해제는 이력에만), candidates = 미확정 후보 (거절은 이력에만).
     * 리비전을 목록보다 먼저 읽는다. 그 사이 관계 변경이 커밋되면 목록이 리비전보다 새로울 수는 있지만 오래될 수는 없다.
     * 그래서 이 리비전을 저장해 두고 나중에 비교하는 쪽은 "최신이 아닌데 최신"으로 잘못 보지 않는다 (반대로 한 번 더 재확인할 뿐).
     */
    @Transactional(readOnly = true)
    public DesignDependenciesResponse dependencies(CallerContext caller, long projectId, long itemId) {
        access.requireMember(caller, projectId);
        requireItem(projectId, itemId);
        long revision = relations.currentRevision(projectId);

        // 확정·재확인 필요·후보를 한 번의 조회(한 시점)로 읽어 나눈다. 두 번 읽으면 그 사이 후보가 확정될 때
        // 첫 조회(확정 목록)에도, 두 번째 조회(후보 목록)에도 없는 관계가 생길 수 있다
        List<DesignDependencyResponse> items = new ArrayList<>();
        List<DependencyCandidateResponse> candidates = new ArrayList<>();
        for (DependencyRow row : relations.dependenciesOf(projectId, itemId, List.of(RelationStorageService.CONFIRMED,
                RelationStorageService.NEEDS_RECHECK, RelationStorageService.CANDIDATE))) {
            if (RelationStorageService.CANDIDATE.equals(row.status())) {
                String reason = row.reason();
                if (reason == null) {
                    reason = "";
                }
                candidates.add(new DependencyCandidateResponse(Ids.str(row.fromItemId()), Ids.str(row.toItemId()),
                        reason, false));
            } else {
                items.add(new DesignDependencyResponse(Ids.str(row.fromItemId()), Ids.str(row.toItemId()), DEPENDS_ON,
                        row.origin(), row.status(), row.reason(), Ids.str(row.confirmedBy()), row.confirmedAt()));
            }
        }
        return new DesignDependenciesResponse(revision, items, candidates);
    }

    /** 항목이 한쪽 끝인 관계 변경 이력. 최신순 커서 페이지 (커서 = 마지막 이력 id) */
    @Transactional(readOnly = true)
    public PageResponse<DependencyHistoryResponse> history(CallerContext caller, long projectId, long itemId,
                                                           String cursor, Integer limit) {
        access.requireMember(caller, projectId);
        requireItem(projectId, itemId);
        int size = PageResponse.normalizeLimit(limit);
        long before = PageResponse.beforeId(cursor);
        List<HistoryRow> rows = relations.historyOf(projectId, itemId, before, size + 1);
        return PageResponse.fromRows(rows, size, HistoryRow::id, page -> {
            List<DependencyHistoryResponse> out = new ArrayList<>();
            for (HistoryRow h : page) {
                out.add(new DependencyHistoryResponse(Ids.str(h.id()), Ids.str(h.fromItemId()), Ids.str(h.toItemId()),
                        h.action(), h.originAfter(), h.statusAfter(), Ids.str(h.baselineId()),
                        Ids.str(h.fromVersionId()), Ids.str(h.toVersionId()), Ids.str(h.changeProposalId()),
                        h.relationSetRevision(), h.reason(), Ids.str(h.actorAccountId()), h.createdAt()));
            }
            return out;
        });
    }

    /**
     * 지금 관계 집합 리비전 (변경 영향 후보 저장·MCP-03 최신성 비교용, 서비스 간 호출).
     * 권한 확인을 끝낸 호출자가 부른다. 관계 변경이 한 번도 없으면 0.
     */
    @Transactional(readOnly = true)
    public long currentRevision(long projectId) {
        return relations.currentRevision(projectId);
    }

    private void requireItem(long projectId, long itemId) {
        if (!relations.itemInProject(itemId, projectId)) {
            throw AccessErrors.notFound("design_item_missing project=" + projectId + " item=" + itemId);
        }
    }
}
