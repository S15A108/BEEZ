package com.beez.backend.notification;

import com.beez.backend.common.api.PageResponse;
import java.util.List;

/**
 * NTF-A01 응답. 커서 페이지(공통 {@link PageResponse})에 벨 배지용 unread_count 를 더한 모양이라 PageResponse 를 그대로 쓰지 못한다.
 *
 * @param unreadCount project_id 범위의 읽지 않은 알림 수. unread·cursor 필터와 상관없다
 */
public record NotificationInboxResponse(List<NotificationResponse> items, boolean hasMore, String nextCursor,
                                        long unreadCount) {

    static NotificationInboxResponse of(PageResponse<NotificationResponse> page, long unreadCount) {
        return new NotificationInboxResponse(page.items(), page.hasMore(), page.nextCursor(), unreadCount);
    }
}
