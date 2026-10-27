package com.beez.backend.project;

import jakarta.validation.constraints.Size;

/**
 * PRJ-A04 요청. 생략(null)은 유지, 하나 이상 있어야 한다 (00-common 3절 PATCH).
 * description 은 빈 문자열 "" 을 보내면 지운다. name 은 지울 수 없다 — 빈 이름은 400.
 * name 길이는 앞뒤 공백을 뺀 뒤 서비스(requireName)에서 본다.
 */
public record UpdateProjectRequest(
        String name,
        @Size(max = ProjectService.DESCRIPTION_MAX) String description
) {
}
