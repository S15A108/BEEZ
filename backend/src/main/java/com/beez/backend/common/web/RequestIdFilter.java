package com.beez.backend.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청마다 X-Request-Id 를 붙이고(MDC → 로그 패턴의 [%X{requestId}]) 응답 헤더로 돌려준다.
 * 들어온 요청에 X-Request-Id 가 있으면 그대로 쓴다 (프론트·AI 서버와 추적 ID 를 이어 붙이기 위함).
 * /api/** 요청은 한 줄 접근 로그를 남긴다. actuator·swagger 는 제외.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final Logger access = LoggerFactory.getLogger("com.beez.access");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String incoming = req.getHeader(HEADER);
        String id = (incoming != null && !incoming.isBlank() && incoming.length() <= 64)
                ? incoming
                : UUID.randomUUID().toString().substring(0, 8);
        MDC.put(MDC_KEY, id);
        res.setHeader(HEADER, id);
        long started = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            if (req.getRequestURI().startsWith("/api/")) {
                long ms = (System.nanoTime() - started) / 1_000_000;
                access.info("{} {} -> {} ({} ms)", req.getMethod(), req.getRequestURI(), res.getStatus(), ms);
            }
            MDC.remove(MDC_KEY);
        }
    }
}
