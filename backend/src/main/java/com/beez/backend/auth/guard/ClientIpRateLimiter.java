package com.beez.backend.auth.guard;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.concurrent.LogThrottle;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 메일을 보내는 비로그인 API(ACC-A11 가입 인증 코드, ACC-A07 임시 비밀번호)의 IP당 한도 (Q04, S15P31A108-273).
 *
 * <ul>
 *   <li><b>두 API 가 카운터 하나를 같이 쓴다.</b> 따로 두면 번갈아 불러 한도가 두 배가 되고, 정상 사용자는 둘을 합쳐도
 *       한 시간에 몇 번이면 충분하다.</li>
 *   <li>컨트롤러가 서비스보다 먼저 부른다. 그래서 409 email_already_exists·이메일당 429·계정 없는 202 요청도 모두 센다 —
 *       가입 여부 조회와 bcrypt 계산을 IP 한도 안으로 묶으려는 것이다. 이 한도로 거부한 요청은 세지 않는다
 *       (같은 공유기 뒤 사용자가 한도가 풀린 뒤에도 계속 막히지 않게).</li>
 *   <li>IP 는 {@code getRemoteAddr()} 다. {@code server.forward-headers-strategy=native} 라 Nginx 가 넘긴 X-Forwarded-For 에서
 *       Tomcat 이 실제 클라이언트 주소를 골라 넣는다 (내부망 주소만 프록시로 믿는다).</li>
 *   <li>메모리에만 센다 — 재시작하면 처음부터 다시 세고, 인스턴스가 둘 이상이면 따로 센다 (Q04: 서버 1대라 허용).
 *       IP 마다 최근 요청 시각을 한도 개수까지만 들고, 창이 지난 IP 는 주기적으로 지운다.</li>
 * </ul>
 */
@Component
public class ClientIpRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ClientIpRateLimiter.class);

    private final AuthGuardProperties props;
    private final ConcurrentHashMap<String, Deque<Instant>> hits = new ConcurrentHashMap<>();
    private final LogThrottle warnThrottle = new LogThrottle(Duration.ofMinutes(10));

    public ClientIpRateLimiter(AuthGuardProperties props) {
        this.props = props;
    }

    /** 한도 안이면 이번 요청을 세고, 넘었으면 429 rate_limited. */
    public void acquire(String clientIp) {
        Instant now = Instant.now();
        if (tryAcquire(clientIp, now)) {
            return;
        }
        long skipped = warnThrottle.tryPass(now);
        if (skipped >= 0) {
            log.warn("가입 인증·비밀번호 찾기 IP 한도 도달: ip={} limit={} window={} suppressed_since_last={}", clientIp,
                    props.ipLimit(), props.ipLimitWindow(), skipped);
        }
        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                "이 네트워크에서 요청이 많습니다. 잠시 뒤에 다시 시도하세요");
    }

    boolean tryAcquire(String clientIp, Instant now) {
        boolean[] acquired = {false};
        hits.compute(clientIp, (ip, times) -> {
            Deque<Instant> q = times == null ? new ArrayDeque<>() : times;
            dropExpired(q, now);
            if (q.size() < props.ipLimit()) {
                q.addLast(now);
                acquired[0] = true;
            }
            return q;
        });
        return acquired[0];
    }

    /** 창 안에서 이 IP 가 쓴 횟수. 시험용. */
    int used(String clientIp, Instant now) {
        Deque<Instant> q = hits.computeIfPresent(clientIp, (ip, times) -> {
            dropExpired(times, now);
            return times;
        });
        return q == null ? 0 : q.size();
    }

    /** 창이 지난 IP 를 표에서 뺀다. 주소를 바꿔 가며 보내도 표가 한 창 분량보다 커지지 않게 한다. */
    @Scheduled(fixedDelayString = "${beez.auth-guard.ip-purge-millis:600000}")
    public void purge() {
        Instant now = Instant.now();
        for (String ip : hits.keySet()) {
            hits.computeIfPresent(ip, (k, times) -> {
                dropExpired(times, now);
                return times.isEmpty() ? null : times;
            });
        }
    }

    private void dropExpired(Deque<Instant> times, Instant now) {
        Instant since = now.minus(props.ipLimitWindow());
        while (!times.isEmpty() && !times.peekFirst().isAfter(since)) {
            times.pollFirst();
        }
    }
}
