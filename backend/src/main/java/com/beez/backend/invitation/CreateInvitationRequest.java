package com.beez.backend.invitation;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * MEM-A01 요청. 셋 다 선택이다.
 * <ul>
 *   <li>{@code email} — 링크 초대(기존 호환). 메일은 보내지 않고 응답의 invite_url 을 팀장이 전달한다. 빈 문자열은 "지정 안 함".</li>
 *   <li>{@code emails} — 이메일 초대(-220). 이메일마다 대기 초대를 만들고 서버가 메일을 보낸다. email 과 함께 쓸 수 없다.</li>
 *   <li>{@code message} — 초대 메일에 넣는 '함께 보낼 말'. 메일을 보내는 emails 와만 쓴다.</li>
 * </ul>
 * 한 번에 보낼 수 있는 이메일 수({@link #MAX_EMAILS})와 message 길이는 Q05 가 정하기 전 기본안이다.
 */
public record CreateInvitationRequest(
        @Email @Size(max = 254) String email,
        @Size(min = 1, max = MAX_EMAILS) List<@NotBlank @Email @Size(max = 254) String> emails,
        @Size(max = MAX_MESSAGE) String message) {

    public static final int MAX_EMAILS = 20;
    public static final int MAX_MESSAGE = 500;

    /** @Email 검증 전에 앞뒤 공백을 지운다 (회원가입 RegisterRequest 와 같은 이유) */
    public CreateInvitationRequest {
        email = email == null || email.isBlank() ? null : email.trim();
        emails = emails == null ? null : emails.stream().map(e -> e == null ? null : e.trim()).toList();
        message = message == null || message.isBlank() ? null : message.strip();
    }
}
