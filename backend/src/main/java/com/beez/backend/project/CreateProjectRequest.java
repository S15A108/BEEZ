package com.beez.backend.project;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** PRJ-A01 요청. 이름은 앞뒤 공백을 뺀 뒤 1~100자 (서비스에서 다시 확인), 설명은 2000자 이하. 길이는 FE 와 합의할 값이다. */
public record CreateProjectRequest(
        @NotBlank @Size(max = ProjectService.NAME_MAX) String name,
        @Size(max = ProjectService.DESCRIPTION_MAX) String description
) {
}
