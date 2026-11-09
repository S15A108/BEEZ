package com.beez.backend.common.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 종료 때 보내던 메일 (S15P31A108-334). 배포 재시작으로 보내던 초대·임시 비밀번호 메일이 조용히 사라지던 문제. */
class AsyncMailSenderTest {

    private static final MailMessage MESSAGE = new MailMessage("a@example.com", "s", "t");

    /**
     * 깨지면: 종료(배포 재시작)가 보내던 메일을 기다리지 않아 메일이 사라지거나(-220 InvitationService), 종료 뒤에 들어온 발송이
     * 실패 처리(임시 비밀번호 행 닫기)도 없이 버려진다.
     */
    @Test
    void 종료는_보내던_메일을_끝까지_기다리고_그_뒤_발송은_실패로_처리한다() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger sent = new AtomicInteger();
        MailGateway slow = m -> {
            started.countDown();
            sleep(300);
            sent.incrementAndGet();
        };
        AsyncMailSender sender = sender(slow, Duration.ofSeconds(5));
        AtomicInteger delivered = new AtomicInteger();
        sender.send(MESSAGE, delivered::incrementAndGet, e -> { });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        sender.stop();
        assertThat(sent.get()).as("종료가 발송을 기다림").isEqualTo(1);
        assertThat(delivered.get()).isEqualTo(1);

        AtomicInteger failed = new AtomicInteger();
        sender.send(MESSAGE, () -> { }, e -> failed.incrementAndGet());
        assertThat(failed.get()).as("종료 뒤 발송은 바로 실패 처리").isEqualTo(1);
        assertThat(sender.inFlight()).isZero();
    }

    /** 깨지면: SMTP 가 멈춘 채면 종료가 끝나지 않아 docker 가 30초 뒤 강제로 끈다 — 그 사이 다른 종료 처리도 못 한다. */
    @Test
    void 종료_대기는_정한_시간까지만() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        MailGateway stuck = m -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        AsyncMailSender sender = sender(stuck, Duration.ofMillis(200));
        sender.send(MESSAGE, () -> { }, e -> { });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        long begin = System.nanoTime();
        sender.stop();
        assertThat(Duration.ofNanos(System.nanoTime() - begin)).isLessThan(Duration.ofSeconds(3));
        release.countDown();
    }

    private static AsyncMailSender sender(MailGateway gateway, Duration wait) {
        AsyncMailSender sender = new AsyncMailSender(gateway,
                new BeezMailProperties(BeezMailProperties.Mode.LOG, "from@example.com", false, wait));
        sender.start();
        return sender;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
