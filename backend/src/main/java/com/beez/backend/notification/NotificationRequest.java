package com.beez.backend.notification;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 알림 요청 한 건 = 수신자 한 명 (계약 docs/design/10-notification-activity-contract.md 3절).
 * 수신자·제목·본문은 호출 도메인이 정해서 넘긴다. 제목·본문에 문서 내용·의견 원문·토큰 값을 넣지 않는다.
 *
 * <p>형식 검사는 여기서 한다. 어긋나면 {@link IllegalArgumentException} — 사용자 입력이 아니라 호출 코드 버그라
 * 도메인 처리도 함께 롤백된다(계약 2.2). 길이 초과는 오류가 아니다 — {@link NotificationRequester} 가 잘라 저장한다.
 *
 * @param notificationKey    {@code {대상}:{ID}:{동작}[:구분]:to:{계정 ID}}. 도메인 사실만으로 정해지게(난수·현재 시각 금지). DB dedupe_key
 * @param recipientAccountId 받는 사람 계정. 활성 멤버가 아니면 저장 때 건너뛴다
 * @param type               {@code DOCUMENT_APPROVED} 같은 유형(계약 6.1). 상수는 소유 도메인 코드에 둔다
 * @param documentId         {@code targetType=DOCUMENT_VERSION} 이면 필수
 */
public record NotificationRequest(String notificationKey, Long projectId, Long recipientAccountId, String type,
                                  String title, String body, NotificationTargetType targetType, Long targetId,
                                  Long documentId) {

    /** 계약 5절. 대상 조각은 영문 소문자와 '-', 그 뒤 조각이 하나 이상 */
    static final Pattern KEY_FORMAT = Pattern.compile("^[a-z][a-z0-9-]*(:[A-Za-z0-9_-]+)+$");
    static final int KEY_MAX_LENGTH = 200;
    /** 계약 6절. {대상}_{과거형 동작} 대문자 스네이크 */
    static final Pattern TYPE_FORMAT = Pattern.compile("^[A-Z][A-Z0-9_]{2,63}$");

    public NotificationRequest {
        Objects.requireNonNull(notificationKey, "notificationKey");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(recipientAccountId, "recipientAccountId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(targetId, "targetId");
        if (notificationKey.length() > KEY_MAX_LENGTH || !KEY_FORMAT.matcher(notificationKey).matches()) {
            throw new IllegalArgumentException("notification key does not match the contract format: " + notificationKey);
        }
        if (!TYPE_FORMAT.matcher(type).matches()) {
            throw new IllegalArgumentException("notification type does not match the contract format: " + type);
        }
        if (title.isBlank() || body.isBlank()) {
            throw new IllegalArgumentException("notification title and body are required: " + notificationKey);
        }
        if (targetType == NotificationTargetType.DOCUMENT_VERSION && documentId == null) {
            throw new IllegalArgumentException("documentId is required for DOCUMENT_VERSION: " + notificationKey);
        }
    }
}
