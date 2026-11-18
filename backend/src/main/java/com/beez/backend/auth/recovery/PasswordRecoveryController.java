package com.beez.backend.auth.recovery;

import com.beez.backend.auth.guard.ClientIpRateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** ACC-A07 임시 비밀번호 발급. PUBLIC (SecurityConfig 의 /api/auth/password/**). */
@Tag(name = "auth", description = "회원가입·로그인·로그아웃")
@RestController
@RequestMapping("/api/auth/password")
public class PasswordRecoveryController {

    private final PasswordRecoveryService recovery;
    private final ClientIpRateLimiter ipLimiter;

    public PasswordRecoveryController(PasswordRecoveryService recovery, ClientIpRateLimiter ipLimiter) {
        this.recovery = recovery;
        this.ipLimiter = ipLimiter;
    }

    public record ForgotRequest(@NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 50) String name) {

        /** 가입 요청(RegisterRequest)과 같이 @Email 검증 전에 앞뒤 공백을 지운다 */
        public ForgotRequest {
            email = email == null ? null : email.trim();
        }
    }

    public record ForgotResponse(String message) {
    }

    @Operation(summary = "임시 비밀번호 발급 (ACC-A07). 이메일·이름이 맞을 때만 메일을 보내지만 응답은 항상 같은 202")
    @PostMapping("/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ForgotResponse forgot(@Valid @RequestBody ForgotRequest body, HttpServletRequest request) {
        // 계정 없는 요청도 bcrypt 를 돌리므로 서비스보다 먼저 센다. 가입 인증(ACC-A11)과 같은 카운터다 (Q04, -273)
        ipLimiter.acquire(request.getRemoteAddr());
        return new ForgotResponse(recovery.request(body.email(), body.name()));
    }
}
