package com.beez.backend.account;

import com.beez.backend.account.AccountWithdrawalService.WithdrawalCheck;
import com.beez.backend.auth.AccountPasswordService;
import com.beez.backend.auth.AccountSessions;
import com.beez.backend.common.auth.CallerContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 계정 (ACC-A04·A05·A06·A10·A13). 세션 정리는 업무 트랜잭션이 커밋된 뒤 여기서 한다 ({@link AccountSessions}).
 */
@Tag(name = "account", description = "내 계정")
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final AccountService accounts;
    private final AccountPasswordService passwords;
    private final AccountWithdrawalService withdrawals;
    private final AccountSessions sessions;

    public MeController(AccountService accounts, AccountPasswordService passwords,
                        AccountWithdrawalService withdrawals, AccountSessions sessions) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.withdrawals = withdrawals;
        this.sessions = sessions;
    }

    public record ChangePasswordRequest(@NotNull String currentPassword, @NotNull String newPassword) {
    }

    public record WithdrawRequest(@NotNull String password) {
    }

    @Operation(summary = "내 계정 조회 (ACC-A04)")
    @GetMapping
    public AccountResponse me(CallerContext caller) {
        return AccountResponse.from(accounts.getMe(caller));
    }

    @Operation(summary = "프로필 수정 (ACC-A05). name·avatar 중 하나 이상. avatar 빈 문자열은 지우기")
    @PatchMapping
    public AccountResponse updateProfile(CallerContext caller, @RequestBody(required = false) UpdateProfileRequest body) {
        return AccountResponse.from(accounts.updateProfile(caller, body));
    }

    @Operation(summary = "비밀번호 변경 (ACC-A06). 성공하면 이 세션만 남기고 같은 계정의 다른 세션을 끊는다")
    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(CallerContext caller, @Valid @RequestBody ChangePasswordRequest body,
                               HttpServletRequest req) {
        Account me = passwords.change(caller, body.currentPassword(), body.newPassword());
        HttpSession current = req.getSession(false);
        sessions.endOthers(me.getId(), current == null ? null : current.getId());
    }

    @Operation(summary = "탈퇴 가능 여부 (ACC-A13). 참고용 — 최종 판정은 ACC-A10")
    @GetMapping("/withdrawal-check")
    public WithdrawalCheck withdrawalCheck(CallerContext caller) {
        return withdrawals.check(caller);
    }

    @Operation(summary = "회원 탈퇴 (ACC-A10). 성공하면 이 계정의 세션을 모두 끊는다")
    @PostMapping("/withdraw")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(CallerContext caller, @Valid @RequestBody WithdrawRequest body, HttpServletRequest req) {
        long accountId = withdrawals.withdraw(caller, body.password());
        HttpSession current = req.getSession(false);
        String currentId = current == null ? null : current.getId();
        // 이 세션은 invalidate 로, 다른 브라우저 세션은 저장소에서 지운다. 저장소로 이 세션까지 지우면
        // 응답을 마칠 때 세션 필터가 다시 저장하려 한다
        sessions.endOthers(accountId, currentId);
        if (current != null) {
            current.invalidate();
        }
        SecurityContextHolder.clearContext();
    }
}
