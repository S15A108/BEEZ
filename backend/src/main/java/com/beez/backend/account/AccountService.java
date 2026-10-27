package com.beez.backend.account;

import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.auth.CallerContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 본인 계정 API (ACC-A04~). */
@Service
public class AccountService {

    private final ProjectAccessChecker access;

    public AccountService(ProjectAccessChecker access) {
        this.access = access;
    }

    @Transactional(readOnly = true)
    public Account getMe(CallerContext caller) {
        // 탈퇴한 계정의 세션(다른 브라우저 등)은 requireUser 가 401 로 막는다
        return access.requireUser(caller);
    }
}
