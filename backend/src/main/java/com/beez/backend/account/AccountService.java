package com.beez.backend.account;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.auth.CallerContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 본인 계정 API (ACC-A04~). */
@Service
public class AccountService {

    private final ProjectAccessChecker access;
    private final AccountRepository accounts;

    public AccountService(ProjectAccessChecker access, AccountRepository accounts) {
        this.access = access;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public Account getMe(CallerContext caller) {
        long accountId = access.requireUser(caller);
        // 다른 브라우저에서 탈퇴한 계정의 세션이 남아 있어도 401
        return accounts.findByIdAndDeletedAtIsNull(accountId).orElseThrow(AccessErrors::unauthenticated);
    }
}
