-- 알림 요청의 document_id (S15P31A108-181, 계약 10-notification-activity-contract.md 3·7절).
-- target_type=DOCUMENT_VERSION 이면 화면이 문서 상세 주소를 만들 때 문서 ID 가 필요하다. 값의 필수 여부는 입구(NotificationRequest)가 검사한다.
ALTER TABLE notification ADD COLUMN document_id BIGINT REFERENCES document(id);
