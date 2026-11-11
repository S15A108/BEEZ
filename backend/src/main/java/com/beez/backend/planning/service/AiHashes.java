package com.beez.backend.planning.service;

/**
 * BE 는 문서 해시를 {@code sha256:<hex64>} 로 저장하지만, AI 계약(133·134)은 접두어 없는 소문자 hex 64자만 받고 돌려준다.
 * 저장 값은 그대로 두고, AI 로 보낼 때만 접두어를 떼며 AI 응답과 비교할 때는 양쪽을 같은 형태로 맞춘다.
 */
final class AiHashes {
    private static final String PREFIX = "sha256:";

    private AiHashes() { }

    /** AI 요청에 넣을 형태. 접두어가 없으면 그대로 돌려준다. */
    static Object wire(Object hash) {
        return hash instanceof String s && s.startsWith(PREFIX) ? s.substring(PREFIX.length()) : hash;
    }

    /** 저장된 해시와 AI 가 돌려준 해시가 같은 값인지(접두어 유무는 무시). */
    static boolean same(Object stored, Object fromAi) {
        Object a = wire(stored);
        return a != null && a.equals(wire(fromAi));
    }
}
