package com.beez.backend.account;

import com.beez.backend.common.auth.CallerContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "account", description = "내 계정")
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final AccountService accounts;

    public MeController(AccountService accounts) {
        this.accounts = accounts;
    }

    @Operation(summary = "내 계정 조회 (ACC-A04)")
    @GetMapping
    public AccountResponse me(CallerContext caller) {
        return AccountResponse.from(accounts.getMe(caller));
    }
}
