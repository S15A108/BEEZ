package com.beez.backend.account;

/** ACC-A05 요청. 둘 다 선택이지만 하나 이상 보내야 한다. */
public record UpdateProfileRequest(String name, String avatar) {
}
