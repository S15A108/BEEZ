package com.beez.backend.auth;

import com.beez.backend.account.Account;
import com.beez.backend.account.AccountResponse;
import com.beez.backend.common.access.SessionUser;
import com.beez.backend.invitation.InvitationService;
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

/**
 * ACC-A01 회원가입, ACC-A02 로그인. 로그아웃(ACC-A03)은 SecurityConfig 의 logout 설정이 처리한다.
 * 둘 다 성공하면 그 이메일의 대기 초대를 멤버십으로 바꾼다 (초대 자동 참여, Q05·-220). 가입·로그인 트랜잭션이 커밋된 뒤
 * 따로 부른다 — AuthService 트랜잭션 안에 넣으면 초대 전환이 실패할 때 가입·로그인까지 되돌려진다.
 */
@Tag(name = "auth", description = "회원가입·로그인·로그아웃")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final SecurityContextRepository contextRepository;
    private final InvitationService invitations;

    public AuthController(AuthService auth, SecurityContextRepository contextRepository, InvitationService invitations) {
        this.auth = auth;
        this.contextRepository = contextRepository;
        this.invitations = invitations;
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotNull String password,
            @NotBlank @Size(max = 50) String name,
            // ACC-A12 에서 받은 1회용 토큰. 인증한 이메일과 email 이 같아야 한다 (S15P31A108-219).
            // 없을 때도 명세는 400 email_not_verified 라 @NotBlank 대신 서비스가 판정한다
            @Size(max = 200) String verifiedToken) {

        /** @Email 검증 전에 앞뒤 공백을 지운다 — 붙여 넣은 " a@b.com " 이 가입만 400 이 되지 않게 (로그인은 서비스에서 정규화) */
        public RegisterRequest {
            email = email == null ? null : email.trim();
        }
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    @Operation(summary = "회원가입 (ACC-A01). verified_token(ACC-A12) 필수. 세션은 만들지 않는다 — 로그인을 따로 호출")
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse register(@Valid @RequestBody RegisterRequest body) {
        Account account = auth.register(body.email(), body.password(), body.name(), body.verifiedToken());
        invitations.joinPendingInvitations(account);   // 실패해도 가입은 성공 (안에서 로그만 남긴다)
        return AccountResponse.from(account);
    }

    @Operation(summary = "로그인 (ACC-A02). 성공하면 세션 쿠키 발급")
    @PostMapping("/login")
    public AccountResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest req,
                                 HttpServletResponse res) {
        Account account = auth.authenticate(body.email(), body.password());
        invitations.joinPendingInvitations(account);   // 실패해도 로그인은 성공 (안에서 로그만 남긴다)

        if (req.getSession(false) != null) {
            req.changeSessionId();   // 세션 고정 공격 방지: 로그인 전 세션 ID 를 이어 쓰지 않는다
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new SessionUser(account.getId()), null, List.of()));
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, req, res);

        return AccountResponse.withPasswordState(account);   // must_change_password (ACC-A07 임시 비밀번호, -171)
    }
}
