package com.beez.backend.account;

import com.beez.backend.agenttoken.AgentTokenRepository;
import com.beez.backend.auth.AccountPasswordService;
import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.project.BlockedProjectResponse;
import com.beez.backend.project.ProjectWithdrawalService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 탈퇴 (S15P31A108-171, ACC-A10·A13). 한 트랜잭션에서 프로젝트 정리 → 에이전트 토큰 전부 폐기 → 개인정보 삭제를 한다.
 * 세션 정리는 커밋 뒤 웹 계층(MeController)이 한다.
 * <p>
 * 작업·토큰·문서·회의 기록은 계정 ID 를 가리킨 채 남고, 이름은 '탈퇴한 사용자'로 보인다 ({@link Account#withdraw}).
 */
@Service
public class AccountWithdrawalService {

    private static final Logger log = LoggerFactory.getLogger(AccountWithdrawalService.class);

    private final ProjectAccessChecker access;
    private final AccountRepository accounts;
    private final AccountPasswordService passwords;
    private final ProjectWithdrawalService projects;
    private final AgentTokenRepository tokens;
    private final EntityManager em;

    public AccountWithdrawalService(ProjectAccessChecker access, AccountRepository accounts,
                                    AccountPasswordService passwords, ProjectWithdrawalService projects,
                                    AgentTokenRepository tokens, EntityManager em) {
        this.access = access;
        this.accounts = accounts;
        this.passwords = passwords;
        this.projects = projects;
        this.tokens = tokens;
        this.em = em;
    }

    public record WithdrawalCheck(boolean canWithdraw, List<BlockedProjectResponse> blockedProjects) {
    }

    /** ACC-A13. 참고용 판정. 최종 판정은 {@link #withdraw} 가 요청 시점에 다시 한다. */
    @Transactional(readOnly = true)
    public WithdrawalCheck check(CallerContext caller) {
        Account me = access.requireUser(caller);
        List<BlockedProjectResponse> blocked = projects.blockedProjects(me.getId());
        return new WithdrawalCheck(blocked.isEmpty(), blocked);
    }

    /**
     * ACC-A10. 비밀번호가 틀리면 400 invalid_credentials(세션은 유효하므로 401 이 아니다), 팀 프로젝트의 팀장이면 409.
     *
     * @return 탈퇴한 계정 ID (세션 정리에 쓴다)
     */
    @Transactional
    public long withdraw(CallerContext caller, String password) {
        Account me = access.requireUser(caller);
        // bcrypt 는 느리므로 잠그기 전에 본다
        if (!passwords.matches(me, password)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_credentials", "비밀번호가 맞지 않습니다");
        }
        // 같은 계정의 탈퇴 두 건이 겹치면 뒤의 것은 잠금을 기다린 뒤 탈퇴 상태를 보고 401 이다
        em.refresh(me, LockModeType.PESSIMISTIC_WRITE);
        if (me.getDeletedAt() != null) {
            throw AccessErrors.unauthenticated();
        }
        long accountId = me.getId();
        Instant now = Instant.now();
        projects.leaveAll(accountId, now);
        int revoked = tokens.revokeAllOfAccount(accountId, now);
        me.withdraw(now);
        accounts.saveAndFlush(me);
        log.info("회원 탈퇴: account_id={} revoked_tokens={}", accountId, revoked);
        return accountId;
    }
}
