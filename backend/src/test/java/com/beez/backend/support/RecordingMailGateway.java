package com.beez.backend.support;

import com.beez.backend.common.mail.MailDeliveryException;
import com.beez.backend.common.mail.MailGateway;
import com.beez.backend.common.mail.MailMessage;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 통합 시험용 메일 구현. 보낸 메일을 모아 두고(인증 코드를 꺼내려고), failNext 로 SMTP 실패를 흉내 낸다.
 * IntegrationTestSupport 가 모든 통합 시험 컨텍스트에 같이 넣으므로 컨텍스트가 하나로 유지된다.
 */
public class RecordingMailGateway implements MailGateway {

    private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");

    private final Deque<MailMessage> sent = new ConcurrentLinkedDeque<>();
    private final AtomicBoolean failNext = new AtomicBoolean();

    @Override
    public void send(MailMessage message) {
        if (failNext.getAndSet(false)) {
            throw new MailDeliveryException("시험용 발송 실패");
        }
        sent.add(message);
    }

    /** 다음 발송 한 번을 실패시킨다. */
    public void failNext() {
        failNext.set(true);
    }

    /** 이 주소로 마지막에 보낸 인증 코드. */
    public String lastCodeTo(String email) {
        for (var it = sent.descendingIterator(); it.hasNext(); ) {
            MailMessage m = it.next();
            if (m.to().equals(email)) {
                Matcher matcher = CODE.matcher(m.text());
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        }
        throw new AssertionError("보낸 인증 메일이 없음: " + email);
    }

    public long countTo(String email) {
        return sent.stream().filter(m -> m.to().equals(email)).count();
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {

        @Bean
        @Primary
        RecordingMailGateway recordingMailGateway() {
            return new RecordingMailGateway();
        }
    }
}
