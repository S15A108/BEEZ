package com.beez.backend.auth;

import com.beez.backend.account.Account;
import com.beez.backend.account.AccountResponse;
import com.beez.backend.common.access.SessionUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** ACC-A01 회원가입, ACC-A02 로그인. 로그아웃(ACC-A03)은 SecurityConfig 의 logout 설정이 처리한다. */
@Tag(name = "auth", description = "회원가입·로그인·로그아웃")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final SecurityContextRepository contextRepository;

    public AuthController(AuthService auth, SecurityContextRepository contextRepository) {
        this.auth = auth;
        this.contextRepository = contextRepository;
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotNull String password,
            @NotBlank @Size(max = 50) String name) {

        /** @Email 검증 전에 앞뒤 공백을 지운다 — 붙여 넣은 " a@b.com " 이 가입만 400 이 되지 않게 (로그인은 서비스에서 정규화) */
        public RegisterRequest {
            email = email == null ? null : email.trim();
        }
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    @Operation(summary = "회원가입 (ACC-A01). 세션은 만들지 않는다 — 로그인을 따로 호출")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse register(@Valid @RequestBody RegisterRequest body) {
        return AccountResponse.from(auth.register(body.email(), body.password(), body.name()));
    }

    @Operation(summary = "로그인 (ACC-A02). 성공하면 세션 쿠키 발급")
    @PostMapping("/login")
    public AccountResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest req,
                                 HttpServletResponse res) {
        Account account = auth.authenticate(body.email(), body.password());

        if (req.getSession(false) != null) {
            req.changeSessionId();   // 세션 고정 공격 방지: 로그인 전 세션 ID 를 이어 쓰지 않는다
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new SessionUser(account.getId()), null, List.of()));
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, req, res);

        return AccountResponse.from(account);
    }
}
