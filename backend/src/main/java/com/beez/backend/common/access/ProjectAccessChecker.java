package com.beez.backend.common.access;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * "이 호출자가 이 프로젝트의 활성 멤버인가"를 확인한다. 서비스 메서드 첫 줄에서 부른다.
 *
 * <p>ERD 의 소프트 삭제 칸을 모두 본다 (행을 지우지 않고 시각·플래그로 표시하는 방식).
 * <ul>
 *   <li>{@code project.archived_at} — 삭제된 프로젝트 → 404</li>
 *   <li>{@code account.deleted_at} — 탈퇴한 계정 → 404</li>
 *   <li>{@code project_member.active} — 제외·나간 팀원 → 404</li>
 *   <li>{@code agent_token.revoked_at} + 토큰의 프로젝트·계정 — 폐기됐거나 남의/다른 프로젝트 토큰 → 401</li>
 * </ul>
 * 응답 코드는 -172 합의안: 존재를 숨겨야 하는 것은 404, 인증 문제는 401, 같은 프로젝트 안 권한 부족은 403.
 *
 * <p>계정·프로젝트·멤버 엔티티(전진)가 아직 없어 SQL 로 직접 읽는다. 공통 권한 검사(-170)가 들어오면 이 클래스를 그쪽으로 합친다.
 * JdbcClient 는 Spring 이 제공하는 짧은 SQL 실행 도구다: {@code sql(...)} 에 문장, {@code param(...)} 으로 :이름 자리를 채운다.
 */
@Component
public class ProjectAccessChecker {

    private final JdbcClient jdbc;

    public ProjectAccessChecker(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 활성 멤버만 통과. 에이전트 호출이면 토큰도 확인한다.
     * 실패 사유는 로그에만 남기고 응답은 하나로 통일한다 ({@link NotFound}).
     */
    public Membership requireMember(CallerContext caller, Long projectId) {
        if (caller.agentTokenId() != null) {
            requireUsableToken(caller, projectId);
        }
        Optional<Membership> found = findMembership(caller.accountId(), projectId);
        if (found.isEmpty()) {
            throw NotFound.because("not_member_or_project_gone project=" + projectId + " account=" + caller.accountId());
        }
        return found.get();
    }

    /**
     * 사람 세션의 LEADER 만 통과. 에이전트 토큰은 LEADER 소유라도 403.
     */
    public Membership requireHumanLeader(CallerContext caller, Long projectId) {
        Membership membership = requireMember(caller, projectId);
        if (!caller.isHuman()) {
            // 명세 오류 코드는 403 forbidden 하나다 (human_only 는 명세에 없음). 사유는 메시지로만 구분한다
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "이 동작은 사람만 할 수 있습니다");
        }
        if (!membership.isLeader()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "팀장만 할 수 있습니다");
        }
        return membership;
    }

    /** 활성 멤버십. 프로젝트 삭제·계정 탈퇴·멤버 제외 중 하나라도 해당하면 비어 있다 */
    public Optional<Membership> findMembership(Long accountId, Long projectId) {
        return jdbc.sql("""
                        select pm.id, pm.role
                          from project_member pm
                          join project p on p.id = pm.project_id
                          join account a on a.id = pm.account_id
                         where pm.project_id = :projectId
                           and pm.account_id = :accountId
                           and pm.active = true
                           and p.archived_at is null
                           and a.deleted_at is null
                        """)
                .param("projectId", projectId)
                .param("accountId", accountId)
                .query((rs, rowNum) -> new Membership(rs.getLong("id"), rs.getString("role")))
                .optional();
    }

    /** 특정 멤버 번호가 이 프로젝트의 활성 멤버인지 (담당자 지정·변경). 같은 소프트 삭제 조건을 쓴다 */
    public boolean isActiveMember(Long memberId, Long projectId) {
        Integer count = jdbc.sql("""
                        select count(*)
                          from project_member pm
                          join project p on p.id = pm.project_id
                          join account a on a.id = pm.account_id
                         where pm.id = :memberId
                           and pm.project_id = :projectId
                           and pm.active = true
                           and p.archived_at is null
                           and a.deleted_at is null
                        """)
                .param("memberId", memberId)
                .param("projectId", projectId)
                .query(Integer.class)
                .single();
        return count > 0;
    }

    /**
     * 에이전트 토큰이 이 계정·이 프로젝트 것이고 폐기되지 않았는지. 아니면 401.
     * 토큰은 프로젝트별로 발급되므로(ERD agent_token.project_id) 다른 프로젝트 경로로 쓰면 거절한다.
     * 진짜 토큰 인증(-170)이 들어와도 서비스 쪽 마지막 방어로 남긴다.
     */
    private void requireUsableToken(CallerContext caller, Long projectId) {
        Integer count = jdbc.sql("""
                        select count(*)
                          from agent_token t
                         where t.id = :tokenId
                           and t.account_id = :accountId
                           and t.project_id = :projectId
                           and t.revoked_at is null
                        """)
                .param("tokenId", caller.agentTokenId())
                .param("accountId", caller.accountId())
                .param("projectId", projectId)
                .query(Integer.class)
                .single();
        if (count == 0) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "token_revoked", "에이전트 토큰이 유효하지 않습니다");
        }
    }
}
