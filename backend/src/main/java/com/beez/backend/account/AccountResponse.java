package com.beez.backend.account;

import java.time.Instant;

/** 00-common 10절 Account. ID 는 십진 문자열(3절). */
public record AccountResponse(String id, String email, String name, String avatar, Instant createdAt) {

    public static AccountResponse from(Account a) {
        return new AccountResponse(String.valueOf(a.getId()), a.getEmail(), a.getDisplayName(), a.getAvatarUrl(),
                a.getCreatedAt());
    }
}
