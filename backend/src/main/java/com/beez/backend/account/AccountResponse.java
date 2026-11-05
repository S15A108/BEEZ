package com.beez.backend.account;

import java.time.Instant;

/**
 * 00-common 10절 Account. ID 는 십진 문자열(3절).
 * must_change_password 는 로그인(ACC-A02) 응답 필드다. 새로 고친 화면도 알 수 있게 내 계정 조회·프로필 수정(ACC-A04·A05)에도 싣고,
 * 가입(ACC-A01) 응답은 null 이라 빠진다 (-171).
 */
public record AccountResponse(String id, String email, String name, String avatar, Instant createdAt,
                              Boolean mustChangePassword) {

    public static AccountResponse from(Account a) {
        return new AccountResponse(String.valueOf(a.getId()), a.getEmail(), a.getDisplayName(), a.getAvatarUrl(),
                a.getCreatedAt(), null);
    }

    /** 로그인(ACC-A02)·내 계정 조회·프로필 수정(ACC-A04·A05): 임시 비밀번호로 로그인해 비밀번호를 바꿔야 하는지 함께 준다. */
    public static AccountResponse withPasswordState(Account a) {
        return new AccountResponse(String.valueOf(a.getId()), a.getEmail(), a.getDisplayName(), a.getAvatarUrl(),
                a.getCreatedAt(), a.isMustChangePassword());
    }
}
