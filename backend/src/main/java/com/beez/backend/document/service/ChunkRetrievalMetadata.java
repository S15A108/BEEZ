package com.beez.backend.document.service;

import static com.beez.backend.document.service.DocumentValues.JSON;

import java.util.List;
import java.util.Map;

/** Optional exact source metadata for a semantic search hit, absent on legacy indexes. */
final class ChunkRetrievalMetadata {
    static void addToHit(Map<String, Object> hit, Map<String, Object> row) {
        Object key = row.get("chunk_key");
        if (key == null) return;
        Object spans = row.get("source_spans");
        Object refs = row.get("context_refs");
        if (spans == null || refs == null) throw new IllegalStateException("Incomplete chunk source metadata");
        hit.put("chunk_key", key);
        hit.put("source_spans", JSON.readValue(spans.toString(), List.class));
        hit.put("context_refs", JSON.readValue(refs.toString(), List.class));
    }

    private ChunkRetrievalMetadata() { }
}
