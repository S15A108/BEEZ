package com.beez.backend.project;

import jakarta.validation.constraints.NotBlank;

/**
 * PRJ-A01 요청. 이름은 앞뒤 공백을 뺀 뒤 1~100자, 설명은 공백을 뺀 뒤 2000자 이하.
 * 공백을 빼야 길이를 알 수 있어 길이는 서비스(requireName·normalizeDescription)에서만 본다. 길이는 FE 와 합의할 값이다.
 */
public record CreateProjectRequest(
        @NotBlank String name,
        String description
) {
}
