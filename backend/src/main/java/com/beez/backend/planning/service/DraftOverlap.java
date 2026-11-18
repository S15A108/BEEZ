package com.beez.backend.planning.service;

import static com.beez.backend.designitem.support.DesignJson.JSON;

import com.beez.backend.planning.repository.DesignDraftRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 문서 단독 후보가 이미 있는 것과 겹치는지 표시한다(S15P31A108-389). 지문(종류+핵심 값)이 같으면 후보는 저장하되
 * {@code overlap_kind/overlap_ref} 로 구분한다: 기존 설계 항목(직접 추가한 것 포함) > 이미 채택한 후보 > 이미 폐기한 후보 >
 * 다른 문서 작업에서 아직 결정하지 않은 후보(먼저 저장된 쪽이 새 후보로 남고 뒤에 저장된 쪽이 겹침으로 표시된다).
 * 같은 작업 안에서 이미 저장한 지문은 {@link #seenInJob} 로 걸러 부분이 겹쳐도 한 번만 남긴다.
 */
final class DraftOverlap {
    private final Map<String, Long> items = new HashMap<>();
    private final Map<String, Long> adopted = new HashMap<>();
    private final Map<String, Long> discarded = new HashMap<>();
    private final Map<String, Long> pending = new HashMap<>();
    private final Set<String> seenInJob = new HashSet<>();

    @SuppressWarnings("unchecked")
    DraftOverlap(DesignDraftRepository drafts, long project, long job) {
        for (var item : drafts.liveItems(project)) {
            String fingerprint = DraftFingerprint.of(item.get("kind").toString(), JSON.readValue(item.get("content").toString(), Map.class));
            if (fingerprint != null) items.putIfAbsent(fingerprint, ((Number) item.get("design_item_version_id")).longValue());
        }
        for (var row : drafts.decidedFingerprints(project)) {
            var target = "ADOPTED".equals(row.get("status")) ? adopted : discarded;
            target.putIfAbsent(row.get("fingerprint").toString(), ((Number) row.get("id")).longValue());
        }
        for (var row : drafts.pendingFingerprints(project, job)) pending.putIfAbsent(row.get("fingerprint").toString(), ((Number) row.get("id")).longValue());
        seenInJob.addAll(drafts.jobFingerprints(job));
    }

    /** 지금 살아 있는 설계 항목의 지문 → 설계 항목 버전 id. 채택 직전에 같은 항목이 이미 생겼는지 볼 때 쓴다. */
    @SuppressWarnings("unchecked")
    static Map<String, Long> liveFingerprints(DesignDraftRepository drafts, long project) {
        var live = new HashMap<String, Long>();
        for (var item : drafts.liveItems(project)) {
            String fingerprint = DraftFingerprint.of(item.get("kind").toString(), JSON.readValue(item.get("content").toString(), Map.class));
            if (fingerprint != null) live.putIfAbsent(fingerprint, ((Number) item.get("design_item_version_id")).longValue());
        }
        return live;
    }

    /** 후보 행에 지문과 겹침 표시를 채운다. 같은 작업에서 이미 저장한 지문이면 false(저장하지 않는다). */
    @SuppressWarnings("unchecked")
    boolean mark(Map<String, Object> row) {
        String fingerprint = DraftFingerprint.of(row.get("kind").toString(), JSON.readValue(row.get("after").toString(), Map.class));
        row.put("fingerprint", fingerprint);
        if (fingerprint == null) return true;
        if (!seenInJob.add(fingerprint)) return false;
        Long ref;
        if ((ref = items.get(fingerprint)) != null) { row.put("overlapKind", "EXISTING_ITEM"); row.put("overlapRef", ref); }
        else if ((ref = adopted.get(fingerprint)) != null) { row.put("overlapKind", "ADOPTED_CANDIDATE"); row.put("overlapRef", ref); }
        else if ((ref = discarded.get(fingerprint)) != null) { row.put("overlapKind", "DISCARDED_CANDIDATE"); row.put("overlapRef", ref); }
        else if ((ref = pending.get(fingerprint)) != null) { row.put("overlapKind", "PENDING_CANDIDATE"); row.put("overlapRef", ref); }
        return true;
    }
}
