package com.beez.backend.auth;

import com.beez.backend.auth.agent.AgentTokenException;
import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.api.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 필터 단계(컨트롤러 진입 전)에서 막힌 요청을 공통 오류 본문으로 응답한다. @RestControllerAdvice 는 여기까지 닿지 않는다.
 * 401: 세션 없음·만료, 에이전트 토큰 무효. 403: 필터 단계 인가 실패(현재는 거의 없음 — 역할 검사는 서비스의 requireMember 몫).
 */
@Component
public class SecurityErrorWriter implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper json;

    public SecurityErrorWriter(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest req, HttpServletResponse res, AuthenticationException e) throws IOException {
        String code = (e instanceof AgentTokenException agent) ? agent.getCode() : "unauthenticated";
        write(res, HttpStatus.UNAUTHORIZED, code, AccessErrors.UNAUTHENTICATED_MESSAGE);
    }

    @Override
    public void handle(HttpServletRequest req, HttpServletResponse res, AccessDeniedException e) throws IOException {
        write(res, HttpStatus.FORBIDDEN, "forbidden", AccessErrors.FORBIDDEN_MESSAGE);
    }

    private void write(HttpServletResponse res, HttpStatus status, String code, String message) throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        json.writeValue(res.getWriter(), ApiErrorResponse.of(code, message));
    }
}
