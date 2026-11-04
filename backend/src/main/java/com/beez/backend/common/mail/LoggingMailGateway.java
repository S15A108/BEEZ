package com.beez.backend.common.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 보내지 않고 로그에만 남기는 구현 (beez.mail.mode=log). 로컬·테스트용이다.
 * 본문에는 인증 코드 같은 비밀이 들어가므로 logBody 가 켜진 경우(local 프로필)에만 본문을 찍는다.
 */
public class LoggingMailGateway implements MailGateway {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailGateway.class);

    private final boolean logBody;

    public LoggingMailGateway(boolean logBody) {
        this.logBody = logBody;
    }

    @Override
    public void send(MailMessage message) {
        if (logBody) {
            log.info("메일(로그 구현, 보내지 않음) to={} subject={}\n{}", message.to(), message.subject(), message.text());
        } else {
            log.info("메일(로그 구현, 보내지 않음) subject={} (본문은 beez.mail.log-body=true 일 때만 찍음)",
                    message.subject());
        }
    }
}
