package com.beez.backend.common.api;

import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * API 의 ID 는 "BIGINT 를 십진 문자열로" 주고받는다 (명세 00-common 3절, 예: "101").
 * DTO 는 String, 서비스·엔티티는 Long 을 쓰고 경계에서 이 클래스로 바꾼다.
 * revision_no·lock_version 같은 번호는 ID 가 아니라 정수 그대로다.
 */
public final class Ids {

    private Ids() {
    }

    public static String str(Long id) {
        if (id == null) {
            return null;
        }
        return String.valueOf(id);
    }

    public static List<String> strs(List<Long> ids) {
        List<String> out = new ArrayList<>();
        if (ids == null) {
            return out;
        }
        for (Long id : ids) {
            out.add(String.valueOf(id));
        }
        return out;
    }

    /** null 은 null. 숫자가 아니면 400 validation_failed */
    public static Long parse(String raw, String field) {
        if (raw == null) {
            return null;
        }
        try {
            long value = Long.parseLong(raw.trim());
            if (value <= 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", field + " 형식이 올바르지 않습니다: " + raw);
        }
    }

    public static List<Long> parseAll(List<String> raws, String field) {
        List<Long> out = new ArrayList<>();
        if (raws == null) {
            return out;
        }
        for (String raw : raws) {
            if (raw == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", field + " 에 빈 값이 있습니다");
            }
            out.add(parse(raw, field));
        }
        return out;
    }
}
