package com.beez.backend.common.concurrent;

import static com.beez.backend.designitem.support.DesignJson.conflict;

import com.beez.backend.common.api.ApiException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;

/**
 * 같은 키의 동시 요청 중 하나만 일을 하고 나머지는 그 결과(성공이든 실패든)를 기다려 받는다.
 * 일이 끝나기 전에는 DB 의 멱등 기록으로 구분할 수 없는 느린 호출(AI)에 쓴다.
 *
 * <p>진행 중인 일의 표는 <b>프로세스 안</b>에만 있어 백엔드 인스턴스가 둘 이상이면 서로 보지 못한다(지금은 단일 인스턴스).
 * 늘리게 되면 DB 쪽 진행 중 표시가 필요하다. 본문(지문)이 다른 요청이 같은 키로 오면 멱등 충돌이다.
 */
public class SingleFlight<T> {
    /** 먼저 온 요청을 기다리는 최대 시간. AI 읽기 시간 제한(기본 20초)과 저장 시간보다 충분히 길다. */
    public static final long FOLLOW_WAIT_SECONDS = 120;

    private record Flight<T>(String fingerprint, CompletableFuture<T> result) { }

    private final ConcurrentHashMap<String, Flight<T>> flights = new ConcurrentHashMap<>();
    private final Supplier<ApiException> onTimeout;
    private final Supplier<ApiException> onInterrupted;

    /**
     * @param onTimeout 기다리다 시간이 지났을 때 던질 오류
     * @param onInterrupted 기다리다 중단됐거나 원인을 알 수 없는 실패일 때 던질 오류
     */
    public SingleFlight(Supplier<ApiException> onTimeout, Supplier<ApiException> onInterrupted) {
        this.onTimeout = onTimeout;
        this.onInterrupted = onInterrupted;
    }

    public SingleFlight() {
        this(() -> new ApiException(HttpStatus.GATEWAY_TIMEOUT, "ai_timeout", "처리가 시간 안에 끝나지 않았어요. 잠시 뒤 다시 시도해 주세요."),
                () -> new ApiException(HttpStatus.BAD_GATEWAY, "ai_unavailable", "처리하지 못했어요. 잠시 뒤 다시 시도해 주세요."));
    }

    /**
     * @param key 같은 일을 가리키는 키(프로젝트·계정·작업 종류·Idempotency-Key 등)
     * @param fingerprint 요청 본문의 지문. 같은 키인데 다르면 멱등 충돌
     * @param work 리더만 실행한다. 결과(또는 던진 예외)를 기다리던 요청들이 같이 받는다
     */
    public T run(String key, String fingerprint, Supplier<T> work) {
        Flight<T> mine = new Flight<>(fingerprint, new CompletableFuture<>());
        Flight<T> leader = flights.putIfAbsent(key, mine);
        if (leader != null) return follow(leader, fingerprint);
        try {
            T value = work.get();
            mine.result().complete(value);
            return value;
        } catch (RuntimeException e) {
            mine.result().completeExceptionally(e);
            throw e;
        } finally {
            flights.remove(key, mine);
        }
    }

    private T follow(Flight<T> leader, String fingerprint) {
        if (!leader.fingerprint().equals(fingerprint)) throw conflict("idempotency_conflict");
        try {
            return leader.result().get(FOLLOW_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            throw onInterrupted.get();
        } catch (TimeoutException e) {
            throw onTimeout.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw onInterrupted.get();
        }
    }
}
