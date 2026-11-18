package com.beez.backend.changeproposal.dto;

import java.util.List;
import java.util.Map;

/** CHG-A07 문서 수정 초안 생성 (S15P31A108-233). ID 는 십진 문자열이다. */
public final class DraftDtos {
    private DraftDtos() { }

    public record Request(String documentId, String baseDocumentVersionId, String instruction, String taskId) { }

    /** 미승인 수정안. 원문·제안·기준을 바꾸지 않는다 — 사람이 확인한 뒤 CHG-A01 로 따로 제출한다. */
    public record Draft(String documentId, String baseDocumentVersionId, String afterContent, Generation generation) { }

    /** 00-common Generation. sources·usage 는 AI 가 준 객체를 그대로 싣되 sources 는 이 요청의 문서 버전만 남긴다. */
    public record Generation(String origin, String model, String text, List<Map<String, Object>> sources,
                             List<String> unresolved, List<Map<String, Object>> usage) { }
}
