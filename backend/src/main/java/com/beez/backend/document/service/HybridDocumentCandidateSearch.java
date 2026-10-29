package com.beez.backend.document.service;

import com.beez.backend.common.auth.CallerContext;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Candidate retrieval only: a search hit is not a confirmed design reference. */
@Service
public class HybridDocumentCandidateSearch {
    private final DocumentApplicationService documents;

    public HybridDocumentCandidateSearch(DocumentApplicationService documents) {
        this.documents = documents;
    }

    public record Candidate(Map<String, Object> hit, Set<String> searchModes) {
        public Candidate {
            hit = Map.copyOf(hit);
            searchModes = Set.copyOf(searchModes);
        }
    }

    /** Each search is limited independently. hasMore indicates only known truncation, not recall. */
    public record Result(String baselineId, List<Candidate> candidates,
                         boolean keywordHasMore, boolean semanticHasMore) {
        public Result {
            candidates = List.copyOf(candidates);
        }
    }

    /**
     * Both queries use the existing authorized, current-baseline search. A change of baseline
     * across calls invalidates the combined result. Errors, including unavailable semantic indexes,
     * propagate; they are never converted to an apparently successful empty search.
     * Document-level and chunk-level hits remain distinct, as do document and design-item IDs.
     */
    public Result search(CallerContext caller, long project, String query, String domain, String kind, int limit) {
        String baseline = Objects.toString(documents.baseline(caller, project, null).get("id"));
        Map<String, Object> keyword = documents.search(caller, project, query, "KEYWORD", domain, kind, null, limit);
        Map<String, Object> semantic = documents.search(caller, project, query, "SEMANTIC", domain, kind, null, limit);
        String currentBaseline = Objects.toString(documents.baseline(caller, project, null).get("id"));
        if (!baseline.equals(currentBaseline)) throw DocumentValues.conflict("baseline_changed");

        var candidates = new LinkedHashMap<String, MutableCandidate>();
        add(candidates, keyword, "KEYWORD");
        add(candidates, semantic, "SEMANTIC");
        return new Result(baseline, candidates.values().stream()
                .map(c -> new Candidate(c.hit, c.searchModes)).toList(),
                hasMore(keyword), hasMore(semantic));
    }

    private static final class MutableCandidate {
        final Map<String, Object> hit;
        final Set<String> searchModes = new LinkedHashSet<>();
        MutableCandidate(Map<String, Object> hit) { this.hit = hit; }
    }

    private static boolean hasMore(Map<String, Object> response) {
        if (!(response.get("has_more") instanceof Boolean value)) {
            throw new IllegalStateException("invalid document search response");
        }
        return value;
    }

    private static void add(Map<String, MutableCandidate> candidates, Map<String, Object> response, String mode) {
        if (!(response.get("items") instanceof List<?> items)) {
            throw new IllegalStateException("invalid document search response");
        }
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> raw) || !(raw.get("type") instanceof String type)
                    || !(raw.get("id") instanceof String id)) {
                throw new IllegalStateException("invalid document search hit");
            }
            var hit = new LinkedHashMap<String, Object>();
            raw.forEach((key, value) -> {
                if (!(key instanceof String)) throw new IllegalStateException("invalid document search field");
                hit.put((String) key, value);
            });
            MutableCandidate candidate = candidates.computeIfAbsent(type + ":" + id, ignored -> new MutableCandidate(hit));
            candidate.searchModes.add(mode);
        }
    }
}
