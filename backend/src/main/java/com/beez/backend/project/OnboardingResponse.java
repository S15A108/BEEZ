package com.beez.backend.project;

import com.beez.backend.document.DocumentKind;
import java.util.List;

/**
 * PRJ-A06 초기 설정 현황. required_documents 는 필수 4종을 문서가 없어도 모두 담는다.
 * RequiredDocument 의 선택 필드(document_id·latest_version_id·working_copy_status·index_ready, 수정안 요청)는 아직 내리지 않는다.
 */
public record OnboardingResponse(
        List<RequiredDocument> requiredDocuments,
        boolean baselinePublished,
        int memberCount,
        boolean myAgentConnected
) {
    /** 명세 00-common RequiredDocument. approved 는 승인본(APPROVED 버전)이 있다는 뜻이다. */
    public record RequiredDocument(DocumentKind kind, boolean uploaded, boolean approved) {
    }
}
