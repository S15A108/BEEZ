package com.beez.backend.designchange.service;

import com.beez.backend.common.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.http.HttpStatus;

/**
 * get_design_changes 의 페이지 cursor. 명세 공통 3절: 서버의 불투명 값이며 숫자 offset 이 아니다.
 *
 * <p>담는 것 네 가지. 첫 페이지 시점의 "최신 기준 번호(ceiling)"를 넣어 두는 이유는, 페이지를 넘기는 사이에
 * 새 발행이 생겨도 같은 목록을 이어서 읽게 하기 위해서다 (공통 3절 "기준 번호는 모든 페이지를 읽기 전에 올리지 않는다").
 * since·task 는 다른 조건으로 cursor 를 재사용하는 걸 막으려고 같이 넣는다.
 *
 * @param ceiling     첫 페이지 때의 최신 기준 번호. current_baseline 도 이 번호로 고정해서 돌려준다
 * @param lastVersion 직전 페이지의 마지막 항목 번호. 다음 페이지는 이보다 작은 번호부터
 * @param since       요청의 since_version
 * @param taskId      요청의 task_id (없으면 null)
 */
record ChangesCursor(int ceiling, int lastVersion, int since, Long taskId) {

    private static final String VERSION = "v1";

    /** Base64 는 글자를 알아보기 어렵게 바꾸는 표준 인코딩이다. 암호화가 아니라 "건드리지 말라"는 표시일 뿐이다 */
    String encode() {
        String task;
        if (taskId == null) {
            task = "";
        } else {
            task = String.valueOf(taskId);
        }
        String raw = VERSION + "|" + ceiling + "|" + lastVersion + "|" + since + "|" + task;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 형식이 깨졌으면 400 validation_failed (공통 4절 400 = 형식 오류) */
    static ChangesCursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 5 || !VERSION.equals(parts[0])) {
                throw new IllegalArgumentException();
            }
            Long task = null;
            if (!parts[4].isEmpty()) {
                task = Long.parseLong(parts[4]);
            }
            return new ChangesCursor(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]), task);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "cursor 형식이 올바르지 않습니다");
        }
    }

    /** 같은 조건(since, task)에서 만든 cursor 인지. 아니면 400 */
    void requireSameQuery(int requestSince, Long requestTaskId) {
        boolean sameTask;
        if (taskId == null) {
            sameTask = requestTaskId == null;
        } else {
            sameTask = taskId.equals(requestTaskId);
        }
        if (since != requestSince || !sameTask) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "cursor 가 다른 조회 조건에서 만들어졌습니다");
        }
    }
}
