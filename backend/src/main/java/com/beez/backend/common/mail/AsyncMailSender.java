package com.beez.backend.common.mail;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

/**
 * 응답을 기다리게 하지 않을 메일을 따로(virtual thread) 보낸다 (S15P31A108-334). 초대 메일(MEM-A01)·임시 비밀번호(ACC-A07)가 쓴다.
 * 실제 전송은 {@link MailGateway}다. 결과가 응답이 되는 발송(가입 인증 ACC-A11, 다시 보내기 MEM-A11)은 이것을 거치지 않고
 * {@link MailGateway}를 직접 부른다.
 *
 * <h2>종료</h2>
 * 배포로 재시작할 때 보내던 메일이 사라지지 않게, 종료 때 새 발송을 받지 않고 보내던 메일을 기다린다. 그래도 남은 것은 건수를
 * WARN 으로 남긴다. 멈추는 시점은 웹 서버가 요청을 다 처리하고 멈춘 <b>뒤</b>({@link #getPhase}), 빈을 없애기(DB 연결 풀 닫기)
 * <b>전</b>이다 — 발송 실패 처리(임시 비밀번호 행 닫기)가 DB 를 쓰고, 종료 직전까지 들어온 요청의 메일도 받아야 해서다.
 * <p>
 * 기다리는 시간은 <b>종료가 시작된 때(컨텍스트 닫기, SIGTERM)부터</b> {@code beez.mail.shutdown-wait}까지다. 웹 서버가 요청을
 * 기다리느라 쓴 시간을 빼고, 웹 서버 대기는 {@code spring.lifecycle.timeout-per-shutdown-phase}(20초)로 묶여 있어
 * 메일 대기가 최소 5초 남는다. 둘을 합쳐도 docker 가 강제로 끄기(compose stop_grace_period 30초) 전에 끝나 WARN 이 남는다.
 * <p>
 * 컨텍스트를 멈췄다 다시 켜면({@link #start}, 테스트 컨텍스트 캐시의 일시정지 등) 발송 스레드를 새로 만든다.
 */
@Component
public class AsyncMailSender implements SmartLifecycle, ApplicationListener<ContextClosedEvent> {

    private static final Logger log = LoggerFactory.getLogger(AsyncMailSender.class);

    private final MailGateway mail;
    private final Duration shutdownWait;
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean running;
    /** 컨텍스트 닫기가 시작된 시각. 없으면(직접 stop) stop 시각부터 잰다 */
    private volatile Instant closingSince;

    public AsyncMailSender(MailGateway mail, BeezMailProperties properties) {
        this.mail = mail;
        this.shutdownWait = properties.shutdownWait();
    }

    /**
     * 메일을 따로 보낸다. 보내면 onSent, 실패하면 onFailure 를 발송 스레드에서 부른다.
     * 종료 중이라 받지 못하면 onFailure 를 이 스레드에서 바로 부른다 (발송 실패와 같게 처리되게).
     * onSent 가 실패해도 발송 실패로 보지 않는다 — 이미 보낸 메일을 실패 처리(임시 비밀번호 닫기 등)하면 안 된다.
     */
    public void send(MailMessage message, Runnable onSent, Consumer<RuntimeException> onFailure) {
        inFlight.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    try {
                        mail.send(message);
                    } catch (RuntimeException e) {
                        onFailure.accept(e);
                        return;
                    }
                    try {
                        onSent.run();
                    } catch (RuntimeException e) {
                        log.warn("메일은 보냈으나 후처리 실패: exception_type={}", e.getClass().getSimpleName());
                    }
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.decrementAndGet();
            onFailure.accept(e);
        }
    }

    /** 아직 끝나지 않은 발송 수. */
    public int inFlight() {
        return inFlight.get();
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        closingSince = Instant.now();
    }

    @Override
    public synchronized void start() {
        if (executor.isShutdown()) {
            executor = Executors.newVirtualThreadPerTaskExecutor();
        }
        closingSince = null;
        running = true;
    }

    @Override
    public synchronized void stop() {
        running = false;
        ExecutorService current = executor;
        current.shutdown();
        Instant since = closingSince == null ? Instant.now() : closingSince;
        Duration left = Duration.between(Instant.now(), since.plus(shutdownWait));
        try {
            if (!current.awaitTermination(Math.max(0, left.toMillis()), TimeUnit.MILLISECONDS)) {
                log.warn("종료 대기 시간 안에 보내지 못한 메일이 있습니다: in_flight={} wait={}", inFlight.get(), shutdownWait);
                current.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("메일 발송 종료 대기가 끊겼습니다: in_flight={}", inFlight.get());
            current.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 웹 서버가 멈춘 뒤(값이 작을수록 늦게 멈춘다). 그 전에 들어온 요청의 메일까지 받는다. */
    @Override
    public int getPhase() {
        return WebServerApplicationContext.START_STOP_LIFECYCLE_PHASE - 1024;
    }
}
