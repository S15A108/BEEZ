package com.beez.backend.common.api;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 컨트롤러를 거치지 않는 호출(MCP 어댑터 → 서비스)에서도 DTO 의 검증 표시(@NotBlank·@NotNull·@Valid)를 적용한다.
 * REST 는 컨트롤러의 @Valid 가 이 일을 하지만, 서비스를 직접 부르면 아무도 검사하지 않는다.
 *
 * <p>Validator 는 Spring 이 만들어 주는 표준 검증기(jakarta.validation)다. {@code validate(obj)} 가 어긴 규칙 목록을 돌려준다.
 * 어긴 게 있으면 REST 와 같은 400 validation_failed 로 바꾼다 (메시지에 칸 이름을 적는다).
 */
@Component
public class InputValidator {

    private final Validator validator;

    public InputValidator(Validator validator) {
        this.validator = validator;
    }

    /** @param field 응답 메시지에 붙일 인수 이름 (예: "plan") */
    public void check(Object input, String field) {
        if (input == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", field + " 은 필수입니다");
        }
        Set<ConstraintViolation<Object>> violations = validator.validate(input);
        if (violations.isEmpty()) {
            return;
        }
        // 메시지 순서가 매번 같도록 칸 이름으로 정렬한다
        List<String> parts = new ArrayList<>();
        for (ConstraintViolation<Object> v : violations) {
            parts.add(field + "." + v.getPropertyPath() + ": " + v.getMessage());
        }
        Collections.sort(parts);
        throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", String.join(", ", parts));
    }

    /** MCP 변경 도구의 idempotency_key (필수). 처리는 Q12 확정 전이라 존재만 본다 */
    public void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "idempotency_key 는 필수입니다");
        }
    }
}
