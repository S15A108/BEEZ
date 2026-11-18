package com.beez.backend.notification;

import com.beez.backend.common.auth.CallerContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 알림함 (S15P31A108-276). 계정 화면(MeController, account 패키지)과 같은 /api/me 아래지만 알림 패키지에 둔다.
 * Idempotency-Key 는 받지 않는다 — 읽음 처리는 몇 번 보내도 같은 상태가 된다({@link NotificationInboxService#markRead}).
 */
@Tag(name = "notification", description = "내 알림함")
@RestController
@RequestMapping("/api/me/notifications")
public class NotificationController {

    private final NotificationInboxService inbox;

    public NotificationController(NotificationInboxService inbox) {
        this.inbox = inbox;
    }

    @Operation(summary = "내 알림함 (NTF-A01)")
    @GetMapping
    public NotificationInboxResponse list(CallerContext caller,
                                          @RequestParam(name = "project_id", required = false) String projectId,
                                          @RequestParam(name = "unread", required = false) Boolean unread,
                                          @RequestParam(name = "cursor", required = false) String cursor,
                                          @RequestParam(name = "limit", required = false) Integer limit) {
        return inbox.inbox(caller, projectId, unread, cursor, limit);
    }

    @Operation(summary = "알림 읽음 처리 (NTF-A02)")
    @PostMapping("/read")
    public NotificationReadResponse read(CallerContext caller, @RequestBody(required = false) NotificationReadRequest request) {
        return inbox.markRead(caller, request);
    }
}
