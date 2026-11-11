package com.beez.backend.planning.dto;

import java.util.List;

/** PLN-A05 설계 초안 생성과 생성 후보 채택 요청. */
public final class DraftDtos {
    private DraftDtos() { }

    /** PLN-A05: 분석이 끝난 원본 버전들. 문서 버전마다 작업이 하나씩 만들어진다. */
    public record CreateRequest(List<String> documentVersionIds, String mode) {
        public CreateRequest(List<String> documentVersionIds) { this(documentVersionIds, null); }

        /** 문서만으로 후보를 만드는 모드(S15P31A108-389). 없으면 기존처럼 질문·답변을 반영한다. */
        public boolean documentOnly() { return "DOCUMENT_ONLY".equals(mode); }
    }

    /** 일괄 채택·폐기 요청의 한 건. 채택은 단건과 같은 확인 값(근거 없음 확인, 충돌 필드 확정)을 건별로 받는다. */
    public record BatchItem(String candidateId, Boolean confirmNoEvidence, List<String> resolvedFields) { }

    public record BatchRequest(List<BatchItem> items) { }

    /**
     * 후보 채택. {@code confirmNoEvidence} 는 근거가 없는(support=NONE) 후보를 알고 채택한다는 확인,
     * {@code resolvedFields} 는 충돌 필드(conflicting_fields)를 사용자가 확정했다는 확인이다.
     */
    public record AdoptRequest(Boolean confirmNoEvidence, List<String> resolvedFields) { }
}
