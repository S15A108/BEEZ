package com.beez.backend.designchange.service;

import com.beez.backend.common.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.http.HttpStatus;

/**
 * get_design_changes 의 result_cursor (S15P31A108-221). "마지막으로 받은 영향 결과 순번" 과, 그 순번을 받은 작업 범위.
 * 서버의 불투명 값이다 (공통 3절). 작업 범위를 넣는 이유: 작업 하나만 보던 진행점으로 프로젝트 전체를 이어 보면
 * 그 사이 다른 작업의 결과를 건너뛴다. 범위가 다르면 400 으로 막고, 새 범위는 result_cursor 없이 다시 시작한다.
 *
 * @param afterSeq 이 순번 다음부터 받는다. 0 = 처음 결과부터
 * @param taskId   요청의 task_id (없으면 null)
 */
record ImpactUpdatesCursor(long afterSeq, Long taskId) {

    private static final String VERSION = "u1";

    /** Base64 는 글자를 알아보기 어렵게 바꾸는 표준 인코딩이다. 암호화가 아니라 "건드리지 말라"는 표시일 뿐이다 */
    String encode() {
        String task;
        if (taskId == null) {
            task = "";
        } else {
            task = String.valueOf(taskId);
        }
        String raw = VERSION + "|" + afterSeq + "|" + task;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 형식이 깨졌으면 400 validation_failed */
    static ImpactUpdatesCursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", -1);
            if (parts.length != 3 || !VERSION.equals(parts[0])) {
                throw new IllegalArgumentException();
            }
            long after = Long.parseLong(parts[1]);
            if (after < 0) {
                throw new IllegalArgumentException();
            }
            Long task = null;
            if (!parts[2].isEmpty()) {
                task = Long.parseLong(parts[2]);
            }
            return new ImpactUpdatesCursor(after, task);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "result_cursor 형식이 올바르지 않습니다");
        }
    }

    /** 같은 작업 범위에서 만든 cursor 인지. 아니면 400 */
    void requireSameTask(Long requestTaskId) {
        boolean sameTask;
        if (taskId == null) {
            sameTask = requestTaskId == null;
        } else {
            sameTask = taskId.equals(requestTaskId);
        }
        if (!sameTask) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed",
                    "result_cursor 가 다른 작업 범위에서 만들어졌습니다. 새 범위는 result_cursor 없이 시작하세요");
        }
    }
}
