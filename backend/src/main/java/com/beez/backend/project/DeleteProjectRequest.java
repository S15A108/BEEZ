package com.beez.backend.project;

import jakarta.validation.constraints.NotNull;

/** PRJ-A05 요청. 현재 프로젝트명을 그대로 입력해야 삭제된다 (다르면 409 confirmation_mismatch). */
public record DeleteProjectRequest(@NotNull String confirmName) {
}
