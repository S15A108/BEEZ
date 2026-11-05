package com.beez.backend.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

/**
 * 한 계정의 웹 세션을 끊는다 (S15P31A108-171). 세션은 spring_session 테이블에 있고(-242) 행의 principal_name 이 계정 ID 라
 * ({@code SessionUser.getName}) 계정의 세션을 모두 찾을 수 있다.
 * <p>
 * 업무 트랜잭션이 커밋된 뒤에 부른다. 세션 저장소는 자기 트랜잭션으로 지우므로, 업무가 되돌려졌는데 로그아웃만 되는 일을 피하려는 것이다.
 * 지우다 실패해도 업무 결과는 그대로다 — 탈퇴 계정의 세션은 권한 검사(requireUser)가 어차피 401 로 막는다.
 */
@Component
public class AccountSessions {

    private static final Logger log = LoggerFactory.getLogger(AccountSessions.class);

    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public AccountSessions(FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.sessions = sessions;
    }

    /**
     * keepSessionId 말고 이 계정의 세션을 모두 지운다. keepSessionId 가 null 이면 전부 지운다.
     *
     * @return 지운 세션 수
     */
    public int endOthers(long accountId, String keepSessionId) {
        int ended = 0;
        try {
            for (String id : sessions.findByPrincipalName(Long.toString(accountId)).keySet()) {
                if (!id.equals(keepSessionId)) {
                    sessions.deleteById(id);
                    ended++;
                }
            }
        } catch (RuntimeException e) {
            log.warn("세션 정리 실패: account_id={} exception_type={}", accountId, e.getClass().getSimpleName());
        }
        return ended;
    }
}
