package com.beez.backend.common.mail;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/** beez.mail.mode 로 MailGateway 구현을 고른다. */
@Configuration
public class MailConfig {

    /**
     * spring.mail.host 가 빈 문자열이어도 Spring Boot 는 JavaMailSender 를 만든다(속성이 "있음"으로 본다).
     * 그러면 localhost 로 붙으려다 실패하므로, 빈 host 는 설정 없음으로 보고 원인이 로그에 바로 보이게 한다.
     */
    @Bean
    MailGateway mailGateway(BeezMailProperties properties, ObjectProvider<JavaMailSender> sender,
                            @Value("${spring.mail.host:}") String host) {
        return switch (properties.mode()) {
            case SMTP -> new SmtpMailGateway(host.isBlank() ? null : sender.getIfAvailable(), properties.from());
            case LOG -> new LoggingMailGateway(properties.logBody());
        };
    }
}
