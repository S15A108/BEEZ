package com.beez.backend.document.service;

import static com.beez.backend.document.service.DocumentValues.JSON;

import java.util.List;
import java.util.Map;

/** Optional exact source metadata for a semantic search hit, absent on legacy indexes. */
public final class ChunkRetrievalMetadata {
    /**
     * 청크 위치 표기 "시작~끝행". 문서 검색 결과와 MCP-01 패킷 근거(sources.location)가 같은 표기를 쓴다(S15P31A108-399).
     * 줄 범위가 없는 locator 는 "원문"(키워드 검색의 문서 단위 결과와 같은 표기)이다.
     */
    public static String location(Map<String, Object> locator) {
        Object start = locator.get("start_line");
        Object end = locator.get("end_line");
        return start != null && end != null ? start + "~" + end + "행" : "원문";
    }

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
