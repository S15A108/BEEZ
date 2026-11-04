package com.beez.backend.common.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * SMTP 로 보내는 구현 (beez.mail.mode=smtp). 접속 값은 spring.mail.* 이다.
 * spring.mail.host 가 비어 있으면 Spring Boot 가 JavaMailSender 를 만들지 않으므로 sender 가 null 로 온다.
 * 그때는 기동을 막지 않고 보낼 때마다 실패시킨다 — 서버 compose 가 MAIL_* 를 넘기기 전에 머지돼도 배포가 깨지지 않게 (-219 PR 노트).
 */
public class SmtpMailGateway implements MailGateway {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailGateway.class);

    private final JavaMailSender sender;
    private final String from;

    public SmtpMailGateway(JavaMailSender sender, String from) {
        this.sender = sender;
        this.from = from;
        if (sender == null) {
            log.error("beez.mail.mode=smtp 인데 spring.mail.host(MAIL_HOST)가 비어 있습니다. 메일 발송은 모두 실패합니다");
        }
    }

    @Override
    public void send(MailMessage message) {
        if (sender == null) {
            throw new MailDeliveryException("SMTP 가 설정되지 않았습니다 (MAIL_HOST)");
        }
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(message.to());
        mail.setSubject(message.subject());
        mail.setText(message.text());
        try {
            sender.send(mail);
        } catch (MailException e) {
            // SMTP 오류 문구에는 받는 사람 주소가 들어갈 수 있어 예외 종류만 남긴다
            log.warn("메일 발송 실패: exception_type={}", e.getClass().getSimpleName());
            throw new MailDeliveryException("메일을 보내지 못했습니다", e);
        }
    }
}
