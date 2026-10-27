package com.beez.backend.common.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 링크·헤더로 건네는 비밀 토큰 (초대 MEM-A01, 에이전트 토큰 -175). 평문은 발급 응답에서 한 번만 주고 DB 에는 해시만 둔다 (NFR-03).
 * 토큰 자체가 32바이트 난수라 추측할 수 없으므로 비밀번호처럼 느린 해시(bcrypt)가 아니라 SHA-256 으로 충분하다 —
 * 그래야 해시로 바로 조회(token_hash UNIQUE)할 수 있다.
 */
public final class SecretTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int BYTES = 32;

    private SecretTokens() {
    }

    /** URL 에 그대로 넣을 수 있는 난수 토큰 (base64url, 43자). */
    public static String generate() {
        byte[] raw = new byte[BYTES];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** 저장·조회용 해시 (SHA-256 16진수 64자). */
    public static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", e);
        }
    }
}
