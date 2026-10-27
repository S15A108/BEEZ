package com.beez.backend.common.access;

import java.io.Serializable;

/**
 * 웹 세션에 저장되는 인증 주체. 계정 ID 만 담는다 — 이름·역할은 세션에 두면 낡으므로 매 요청 DB 에서 읽는다.
 * HttpSession 에 직렬화되어 들어가므로 Serializable.
 */
public record SessionUser(long accountId) implements Serializable {
}
