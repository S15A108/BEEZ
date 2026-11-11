package com.beez.backend.document;

/**
 * 문서마다 보완 질문의 기준이 되는 버전 하나: 승인본(APPROVED)이 있으면 그것, 없으면 최신 초안(DRAFT).
 *
 * <p>기획 분석 화면과 설계 초안(PLN-A05)은 문서 목록의 {@code approved_version ?? latest_draft} 로 이 버전을 고른다.
 * 질문 생성 워커·질문 최신 여부·질문 재생성 가능 판정도 이 정의를 써야 셋이 같은 버전을 본다. 따로 정하면 승인본 뒤에
 * 다시 낸 초안이 검토 중일 때 화면은 새 초안의 질문을, 초안 생성은 승인본의 질문을 보는 식으로 어긋났다(S15P31A108-396).
 * 기준 설계 발행 전에는 승인 때 이전 승인본이 SUPERSEDED 되므로 APPROVED 는 문서당 하나다.
 */
public final class QuestionBasisVersion {
    private QuestionBasisVersion() { }

    /** 문서 ID 식(예: {@code d.id})의 기준 버전 ID 를 돌려주는 SQL 스칼라 서브쿼리. 기준 버전이 없으면 NULL. */
    public static String idOf(String documentIdExpression) {
        return "(SELECT bv.id FROM document_version bv WHERE bv.document_id=" + documentIdExpression
                + " AND bv.status IN ('APPROVED','DRAFT') ORDER BY (bv.status='APPROVED') DESC, bv.version_no DESC LIMIT 1)";
    }
}
