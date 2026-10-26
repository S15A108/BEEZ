package com.beez.backend.common.access;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * "이 호출자가 이 프로젝트의 활성 멤버인가"를 확인한다.
 *
 * <p>계정·프로젝트·멤버 엔티티(S15P31A108-68·69, 전진)가 아직 없어 {@code project_member} 테이블을 SQL 로 직접 읽는다.
 * 그 엔티티가 들어와도 이 클래스 안쪽만 바꾸면 되고, 서비스 쪽 호출은 그대로다.
 *
 * <p>JdbcClient 는 Spring 이 제공하는 짧은 SQL 실행 도구다. {@code sql(...)} 에 문장을 쓰고 {@code param(...)} 으로 ? 자리를 채운 뒤
 * {@code query(...)} 로 결과를 꺼낸다. 엔티티 없이 한두 줄 조회할 때 쓴다.
 */
@Component
public class ProjectAccessChecker {

    private final JdbcClient jdbc;

    public ProjectAccessChecker(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 멤버가 아니면 404 {@code not_found}. (명세: "리소스가 없거나 프로젝트 소속·접근 권한이 없음" 은 404 로 숨긴다)
     */
    public Membership requireMember(CallerContext caller, Long projectId) {
        Optional<Membership> found = findMembership(caller.accountId(), projectId);
        if (found.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "프로젝트를 찾을 수 없거나 접근 권한이 없습니다");
        }
        return found.get();
    }

    /**
     * 사람 세션의 LEADER 만 통과. 에이전트 토큰은 LEADER 소유라도 403.
     */
    public Membership requireHumanLeader(CallerContext caller, Long projectId) {
        Membership membership = requireMember(caller, projectId);
        if (!caller.isHuman()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "human_only", "이 동작은 사람만 할 수 있습니다");
        }
        if (!membership.isLeader()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "팀장만 할 수 있습니다");
        }
        return membership;
    }

    public Optional<Membership> findMembership(Long accountId, Long projectId) {
        return jdbc.sql("""
                        select id, role
                          from project_member
                         where project_id = :projectId
                           and account_id = :accountId
                           and active = true
                        """)
                .param("projectId", projectId)
                .param("accountId", accountId)
                .query((rs, rowNum) -> new Membership(rs.getLong("id"), rs.getString("role")))
                .optional();
    }

    /** 특정 멤버 번호가 이 프로젝트의 활성 멤버인지. 담당자 지정·변경 때 쓴다. */
    public boolean isActiveMember(Long memberId, Long projectId) {
        Integer count = jdbc.sql("""
                        select count(*)
                          from project_member
                         where id = :memberId
                           and project_id = :projectId
                           and active = true
                        """)
                .param("memberId", memberId)
                .param("projectId", projectId)
                .query(Integer.class)
                .single();
        return count > 0;
    }
}
