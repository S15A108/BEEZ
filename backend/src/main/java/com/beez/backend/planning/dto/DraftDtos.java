package com.beez.backend.planning.dto;

import java.util.List;

/** PLN-A05 설계 초안 생성과 생성 후보 채택 요청. */
public final class DraftDtos {
    private DraftDtos() { }

    /** PLN-A05: 분석이 끝난 원본 버전들. 문서 버전마다 작업이 하나씩 만들어진다. */
    public record CreateRequest(List<String> documentVersionIds) { }

    /**
     * 후보 채택. {@code confirmNoEvidence} 는 근거가 없는(support=NONE) 후보를 알고 채택한다는 확인,
     * {@code resolvedFields} 는 충돌 필드(conflicting_fields)를 사용자가 확정했다는 확인이다.
     */
    public record AdoptRequest(Boolean confirmNoEvidence, List<String> resolvedFields) { }
}
