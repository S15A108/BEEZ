package com.beez.backend.account;

import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 본인 계정 API (ACC-A04~). */
@Service
public class AccountService {

    /** 가입(ACC-A01 RegisterRequest name @Size(max = 50))과 같은 상한 */
    static final int NAME_MAX = 50;
    /** 아바타 식별값(bee-yellow) 또는 URL. 화면이 고르는 값이라 넉넉히 둔다 */
    static final int AVATAR_MAX = 500;

    private final ProjectAccessChecker access;
    private final AccountRepository accounts;

    public AccountService(ProjectAccessChecker access, AccountRepository accounts) {
        this.access = access;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public Account getMe(CallerContext caller) {
        // 탈퇴한 계정의 세션(다른 브라우저 등)은 requireUser 가 401 로 막는다
        return access.requireUser(caller);
    }

    /**
     * ACC-A05. 보낸 필드만 바꾼다. 이름은 비울 수 없고, 아바타는 빈 문자열이면 지운다.
     * JSON 의 null 과 필드 생략은 구분하지 않는다(둘 다 유지) — 팀원 표시 수정(MEM-A07)과 같은 규칙이다.
     */
    @Transactional
    public Account updateProfile(CallerContext caller, UpdateProfileRequest req) {
        Account me = access.requireUser(caller);
        if (req == null || (req.name() == null && req.avatar() == null)) {
            throw invalid("변경할 필드(name, avatar)가 하나 이상 필요합니다");
        }
        String name = me.getDisplayName();
        if (req.name() != null) {
            name = req.name().strip();
            if (name.isEmpty()) {
                throw invalid("name: 비울 수 없습니다");
            }
            if (name.length() > NAME_MAX) {
                throw invalid("name: " + NAME_MAX + "자 이하로 입력해야 합니다");
            }
        }
        String avatar = me.getAvatarUrl();
        if (req.avatar() != null) {
            avatar = req.avatar().strip();
            if (avatar.isEmpty()) {
                avatar = null;
            } else if (avatar.length() > AVATAR_MAX) {
                throw invalid("avatar: " + AVATAR_MAX + "자 이하로 입력해야 합니다");
            }
        }
        me.changeProfile(name, avatar);
        return accounts.saveAndFlush(me);
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", message);
    }
}
