package com.beez.backend.changediscussion.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** change_comment · change_comment_target SQL. 권한과 업무 조건은 서비스가 본다 */
@Repository
public class ChangeCommentRepository {

    /** 목록·응답용 한 줄. mentionedMemberIds 는 이 프로젝트의 멤버 번호 */
    public record CommentRow(Long id, Long proposalId, Long authorAccountId, String kind, String body,
                             int revisionNo, List<Long> mentionedMemberIds, Instant createdAt) {
    }

    private static final String COLUMNS =
            "id, change_proposal_id, author_account_id, kind, body, proposal_revision_no, created_at";

    private final JdbcClient jdbc;

    public ChangeCommentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 제안의 현재 상태와 제출 리비전 번호 */
    public record ProposalState(String status, int revisionNo) {
    }

    /** 이 프로젝트의 제안 상태. 다른 프로젝트 제안은 없는 것과 같다 */
    public Optional<ProposalState> findProposal(long projectId, long proposalId) {
        return jdbc.sql("select status, revision_no from change_proposal where id = :p and project_id = :project")
                .param("p", proposalId).param("project", projectId)
                .query((rs, n) -> new ProposalState(rs.getString("status"), rs.getInt("revision_no"))).optional();
    }

    /**
     * 쓰기 직전 제안 행을 공유 잠금으로 읽는다. 재제출·거두기·승인(제안 행 갱신)과 겹치면 그쪽이 끝난 뒤의 상태·리비전을 본다.
     */
    public Optional<ProposalState> lockProposalForShare(long projectId, long proposalId) {
        return jdbc.sql("select status, revision_no from change_proposal where id = :p and project_id = :project for share")
                .param("p", proposalId).param("project", projectId)
                .query((rs, n) -> new ProposalState(rs.getString("status"), rs.getInt("revision_no"))).optional();
    }

    /** 같은 키의 동시 요청을 한 줄로 세운다. 트랜잭션이 끝나면 풀린다 */
    public void lockIdempotency(long proposalId, long accountId, String key) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:k, 0))")
                .param("k", "change_comment:" + proposalId + ":" + accountId + ":" + key).query().singleRow();
    }

    /** 멤버 번호 → 계정. 이 프로젝트의 활성 멤버(탈퇴한 계정·삭제된 프로젝트 제외)만 담는다 */
    public Map<Long, Long> activeAccountsByMember(long projectId, Collection<Long> memberIds) {
        Map<Long, Long> out = new HashMap<>();
        if (memberIds.isEmpty()) {
            return out;
        }
        jdbc.sql("""
                select m.id, m.account_id
                  from project_member m
                  join project p on p.id = m.project_id
                  join account a on a.id = m.account_id
                 where m.project_id = :project and m.id in (:ids)
                   and m.active and p.archived_at is null and a.deleted_at is null
                """).param("project", projectId).param("ids", memberIds)
                .query((rs, n) -> out.put(rs.getLong("id"), rs.getLong("account_id"))).list();
        return out;
    }

    public long insert(long proposalId, int revisionNo, long authorAccountId, String kind, String body,
                       Collection<Long> targetAccountIds) {
        Long id = jdbc.sql("""
                insert into change_comment (change_proposal_id, proposal_revision_no, author_account_id, kind, body)
                values (:p, :rev, :a, :kind, :body) returning id
                """).param("p", proposalId).param("rev", revisionNo).param("a", authorAccountId).param("kind", kind)
                .param("body", body).query(Long.class).single();
        for (Long target : targetAccountIds) {
            jdbc.sql("insert into change_comment_target (change_comment_id, account_id) values (:c, :a)")
                    .param("c", id).param("a", target).update();
        }
        return id;
    }

    public Optional<CommentRow> findById(long id) {
        return withTargets(jdbc.sql("select " + COLUMNS + " from change_comment where id = :id")
                .param("id", id).query(ChangeCommentRepository::map).list()).stream().findFirst();
    }

    /**
     * 작성 순서(id 오름차순)로 afterId 뒤를 limit 개. 호출하는 쪽이 limit+1 을 넘겨 다음 페이지가 있는지 본다.
     * DECISION 행은 제외한다 — 승인·반려 결정의 원본은 approval 이다.
     */
    public List<CommentRow> list(long proposalId, Integer revisionNo, long afterId, int limit) {
        return withTargets(jdbc.sql("select " + COLUMNS + " from change_comment"
                        + " where change_proposal_id = :p and id > :after and kind in ('COMMENT','REVIEW_REQUEST')"
                        + " and (cast(:rev as int) is null or proposal_revision_no = :rev)"
                        + " order by id limit :limit")
                .param("p", proposalId).param("rev", revisionNo).param("after", afterId).param("limit", limit)
                .query(ChangeCommentRepository::map).list());
    }

    private static CommentRow map(ResultSet rs, int rowNum) throws SQLException {
        return new CommentRow(rs.getLong("id"), rs.getLong("change_proposal_id"), rs.getLong("author_account_id"),
                rs.getString("kind"), rs.getString("body"),
                rs.getInt("proposal_revision_no"), List.of(), rs.getTimestamp("created_at").toInstant());
    }

    /** 한 번의 쿼리로 모든 의견의 지정 멤버를 붙인다 (의견마다 조회하지 않는다) */
    private List<CommentRow> withTargets(List<CommentRow> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        List<Long> ids = new ArrayList<>();
        for (CommentRow r : rows) {
            ids.add(r.id());
        }
        Map<Long, List<Long>> byComment = new HashMap<>();
        jdbc.sql("""
                select t.change_comment_id, m.id as member_id
                  from change_comment_target t
                  join change_comment c on c.id = t.change_comment_id
                  join change_proposal cp on cp.id = c.change_proposal_id
                  join project_member m on m.project_id = cp.project_id and m.account_id = t.account_id
                 where t.change_comment_id in (:ids)
                 order by t.change_comment_id, m.id
                """).param("ids", ids)
                .query((rs, n) -> byComment.computeIfAbsent(rs.getLong("change_comment_id"), k -> new ArrayList<>())
                        .add(rs.getLong("member_id"))).list();
        List<CommentRow> out = new ArrayList<>();
        for (CommentRow r : rows) {
            out.add(new CommentRow(r.id(), r.proposalId(), r.authorAccountId(), r.kind(), r.body(), r.revisionNo(),
                    byComment.getOrDefault(r.id(), List.of()), r.createdAt()));
        }
        return out;
    }
}
