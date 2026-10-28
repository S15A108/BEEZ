package com.beez.backend.support;

import com.beez.backend.project.ProjectRole;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 권한 시나리오용 데이터를 SQL 로 바로 넣는다. 만든 계정은 모두 {@link #PASSWORD} 로 로그인할 수 있다.
 * <pre>
 * var p = fixtures.projectWithLeader();                       // 팀장 1명짜리 프로젝트
 * long bob = fixtures.account();                              // 계정만
 * long bobMember = fixtures.member(p.projectId(), bob, ProjectRole.MEMBER);
 * fixtures.removeMember(bobMember);                           // 제외·나가기
 * fixtures.archiveProject(p.projectId());                     // 삭제
 * long token = fixtures.agentToken(p.projectId(), bob);       // 에이전트 토큰 행 (멤버만 가질 수 있다)
 * </pre>
 */
public final class AccessFixtures {

    public static final String PASSWORD = "fixture-pass-1";

    private final JdbcTemplate jdbc;
    private final String passwordHash;

    AccessFixtures(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.passwordHash = encoder.encode(PASSWORD);
    }

    public record ProjectWithLeader(long projectId, long leaderAccountId, long leaderMemberId, String leaderEmail) {
    }

    public static String uniqueEmail() {
        return "u-" + UUID.randomUUID().toString().substring(0, 12) + "@example.com";
    }

    public long account() {
        return account(uniqueEmail());
    }

    public long account(String email) {
        return jdbc.queryForObject(
                "INSERT INTO account (email, password_hash, display_name) VALUES (?, ?, ?) RETURNING id",
                Long.class, email, passwordHash, "tester");
    }

    public String emailOf(long accountId) {
        return jdbc.queryForObject("SELECT email FROM account WHERE id = ?", String.class, accountId);
    }

    public long project(long ownerAccountId) {
        return jdbc.queryForObject(
                "INSERT INTO project (name, owner_account_id) VALUES (?, ?) RETURNING id",
                Long.class, "fixture-project", ownerAccountId);
    }

    public long member(long projectId, long accountId, ProjectRole role) {
        return jdbc.queryForObject(
                "INSERT INTO project_member (project_id, account_id, role) VALUES (?, ?, ?) RETURNING id",
                Long.class, projectId, accountId, role.name());
    }

    public ProjectWithLeader projectWithLeader() {
        String email = uniqueEmail();
        long leader = account(email);
        long projectId = project(leader);
        long memberId = member(projectId, leader, ProjectRole.LEADER);
        return new ProjectWithLeader(projectId, leader, memberId, email);
    }

    /** 팀원 제외·프로젝트 나가기 (MEM-A09·A10) 상태로 만든다. */
    public void removeMember(long memberId) {
        jdbc.update("UPDATE project_member SET active = false, left_at = now() WHERE id = ?", memberId);
    }

    /** 프로젝트 삭제 (PRJ-A05) 상태로 만든다. */
    public void archiveProject(long projectId) {
        jdbc.update("UPDATE project SET archived_at = now() WHERE id = ?", projectId);
    }

    /** 계정 탈퇴 (ACC-A10) 상태로 만든다. */
    public void withdraw(long accountId) {
        jdbc.update("UPDATE account SET deleted_at = now(), email = NULL WHERE id = ?", accountId);
    }

    /** 에이전트 토큰 행. 해시는 임의값이라 /mcp 에서 쓸 수 없다 — 쓸 토큰은 발급 API(AGT-A01)로 받는다. */
    public long agentToken(long projectId, long accountId) {
        return jdbc.queryForObject(
                "INSERT INTO agent_token (project_id, account_id, name, token_hash) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, projectId, accountId, "fixture-token", "hash-" + UUID.randomUUID());
    }

    /** 토큰 폐기 (ACC-05, AGT-06) 상태로 만든다. */
    public void revokeToken(long tokenId) {
        jdbc.update("UPDATE agent_token SET revoked_at = now() WHERE id = ?", tokenId);
    }
}
