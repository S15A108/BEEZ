package com.beez.backend.notification;

import java.util.List;

/**
 * NTF-A02 요청. ids 와 all=true 중 하나만 준다. project_id 는 all=true 일 때의 범위.
 * 조합 검사는 {@link NotificationInboxService#markRead} 가 한다.
 */
public record NotificationReadRequest(List<String> ids, Boolean all, String projectId) {
}
