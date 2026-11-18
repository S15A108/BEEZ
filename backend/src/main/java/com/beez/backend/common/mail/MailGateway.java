package com.beez.backend.common.mail;

/**
 * 메일 발송 입구 (S15P31A108-219). 가입 인증 코드가 첫 사용처이고, 초대 메일(-220)·임시 비밀번호(ACC-A07)가 뒤따른다.
 * 구현은 beez.mail.mode 로 고른다 — smtp(운영) 또는 log(로컬·테스트, 실제로 보내지 않음).
 */
public interface MailGateway {

    /**
     * 메일을 보낸다. 돌아오면 SMTP 서버가 받은 것이다.
     *
     * @throws MailDeliveryException 보내지 못했을 때. 호출한 쪽이 업무에 맞는 오류 응답으로 바꾼다
     */
    void send(MailMessage message);
}
