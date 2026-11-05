package com.beez.backend.bee.repository;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** bee_answer · bee_answer_feedback · 현재 상태 계산 SQL. 권한은 서비스가 본다 */
@Repository
public class BeeRepository {

    /** 현재 활동 한 건. kind 는 DOCUMENT_REVIEW 또는 MEETING */
    public record Activity(String kind, long targetId, String title) {
    }

    private final JdbcClient jdbc;

    public BeeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 본인이 이 프로젝트에서 받은 답변이면 피드백을 저장(같은 답변 재전송은 덮어씀)하고 true.
     * 남의 답변·다른 프로젝트 답변은 저장하지 않고 false. 근거 문서 버전은 답변 발급 기록에서 가져온다.
     */
    public boolean upsertFeedback(long projectId, long answerId, long accountId, String rating, String comment) {
        return jdbc.sql("""
                        insert into bee_answer_feedback(answer_id, account_id, rating, comment, document_version_ids)
                        select a.id, a.account_id, :rating, :comment, a.document_version_ids
                          from bee_answer a
                         where a.id = :answer and a.project_id = :project and a.account_id = :account
                        on conflict (answer_id, account_id) do update
                           set rating = excluded.rating, comment = excluded.comment,
                               document_version_ids = excluded.document_version_ids, updated_at = now()
                        """)
                .param("rating", rating).param("comment", comment).param("answer", answerId)
                .param("project", projectId).param("account", accountId).update() == 1;
    }

    /**
     * 실행 중인 문서 검토(최근 staleMinutes 분 안)와 열린 회의 중 가장 최근 하나.
     * 검토는 표시용 문서 버전으로 작성 사본의 기준 버전(없으면 최초 버전)을 쓴다.
     */
    public Optional<Activity> latestActivity(long projectId, int staleMinutes) {
        return jdbc.sql("""
                        select kind, target_id, title from (
                          select 'DOCUMENT_REVIEW' as kind, coalesce(w.base_version_id, w.initial_version_id) as target_id,
                                 d.title as title, r.created_at as at
                            from document_bee_review_request r
                            join document_working_copy w on w.document_id = r.document_id
                            join document d on d.id = w.document_id
                           where d.project_id = :p and r.status = 'RUNNING'
                             and r.created_at > now() - make_interval(mins => :stale)
                          union all
                          select 'MEETING', m.id, m.title, m.created_at
                            from meeting m
                           where m.project_id = :p and m.status = 'OPEN'
                        ) t order by at desc, target_id desc limit 1
                        """)
                .param("p", projectId).param("stale", staleMinutes)
                .query((rs, n) -> new Activity(rs.getString("kind"), rs.getLong("target_id"), rs.getString("title")))
                .optional();
    }
}
