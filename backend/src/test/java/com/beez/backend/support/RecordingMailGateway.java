package com.beez.backend.support;

import com.beez.backend.common.mail.MailDeliveryException;
import com.beez.backend.common.mail.MailGateway;
import com.beez.backend.common.mail.MailMessage;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    /** 주소별 실패 예약. 다른 시험의 비동기 메일이 실패를 대신 가져가지 않게 한다 (failNext 는 아무 메일이나 가져간다) */
    private final Set<String> failNextTo = ConcurrentHashMap.newKeySet();

    /** 주소별로 다음 발송 도중(받기 전에) 한 번 돌릴 일. 발송하는 사이 다른 요청이 끼어드는 경쟁을 만든다 */
    private final Map<String, Runnable> duringNextSendTo = new ConcurrentHashMap<>();

    @Override
    public void send(MailMessage message) {
        Runnable during = duringNextSendTo.remove(message.to());
        if (during != null) {
            during.run();
        }
        if (failNextTo.remove(message.to()) || failNext.getAndSet(false)) {
            throw new MailDeliveryException("시험용 발송 실패");
        }
        sent.add(message);
    }

    /** 다음 발송 한 번을 실패시킨다. */
    public void failNext() {
        failNext.set(true);
    }

    /** 이 주소로 가는 다음 발송 도중에 action 을 발송 스레드에서 돌린다. */
    public void duringNextSendTo(String email, Runnable action) {
        duringNextSendTo.put(email, action);
    }

    /** 이 주소로 가는 다음 발송 한 번을 실패시킨다. */
    public void failNextTo(String email) {
        failNextTo.add(email);
    }

    /** failNextTo 예약이 아직 쓰이지 않았는지 (비동기 발송이 실패를 가져갔는지 기다릴 때). */
    public boolean failPendingTo(String email) {
        return failNextTo.contains(email);
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

    /** 이 주소로 마지막에 보낸 메일. 없으면 null (비동기 발송을 기다릴 때 쓴다). */
    public MailMessage lastTo(String email) {
        for (var it = sent.descendingIterator(); it.hasNext(); ) {
            MailMessage m = it.next();
            if (m.to().equals(email)) {
                return m;
            }
        }
        return null;
    }

    /** 이 주소로 마지막에 보낸 메일 중 본문에 marker 가 든 것. 없으면 null (한 주소로 여러 종류가 갈 때). */
    public MailMessage lastTo(String email, String marker) {
        for (var it = sent.descendingIterator(); it.hasNext(); ) {
            MailMessage m = it.next();
            if (m.to().equals(email) && m.text().contains(marker)) {
                return m;
            }
        }
        return null;
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
