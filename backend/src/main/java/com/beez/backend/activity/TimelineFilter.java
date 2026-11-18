package com.beez.backend.activity;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.Ids;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * MON-A04 필터. 모든 필터는 AND 로 묶고, 페이지를 자르기 전에 건다.
 * 빈 값(?type=)은 필터 없음이다 — '전체' 필터 화면이 빈 값을 그대로 붙여 보내도 열리게(NTF-A01 의 project_id 와 같은 취급).
 *
 * @param memberId 그 멤버(project_member.id) 본인의 행위와 그 멤버 토큰으로 한 에이전트 행위. 나간 멤버도 지난 활동은 보인다.
 *                 다른 프로젝트의 멤버 번호면 맞는 행이 없어 빈 목록이다(존재를 알리지 않는다)
 */
record TimelineFilter(String type, String actorType, Long memberId, Long agentSessionId, Long changeProposalId) {

    static final Set<String> ACTOR_TYPES = Set.of("HUMAN", "AGENT", "BEE", "SYSTEM");

    static TimelineFilter of(String type, String actorType, String memberId, String agentSessionId,
                             String changeProposalId) {
        String actor = blankToNull(actorType);
        if (actor != null) {
            actor = actor.toUpperCase(Locale.ROOT);
            if (!ACTOR_TYPES.contains(actor)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed",
                        "actor_type 은 HUMAN·AGENT·BEE·SYSTEM 중 하나여야 합니다: " + actorType);
            }
        }
        return new TimelineFilter(blankToNull(type), actor,
                Ids.parse(blankToNull(memberId), "member_id"),
                Ids.parse(blankToNull(agentSessionId), "agent_session_id"),
                Ids.parse(blankToNull(changeProposalId), "change_proposal_id"));
    }

    /** a = activity_event 별칭. 걸린 필터만 {@code and …} 로 이어 붙이고 값은 params 에 넣는다. :projectId 는 호출자가 넣는다. */
    String where(MapSqlParameterSource params) {
        StringBuilder sql = new StringBuilder();
        if (type != null) {
            sql.append(" and a.event_type = :type");
            params.addValue("type", type);
        }
        if (actorType != null) {
            sql.append(" and a.actor_kind = :actorType");
            params.addValue("actorType", actorType);
        }
        if (memberId != null) {
            // actor_id 는 사람·에이전트만 채워진다(V29 ck_activity_actor_account) — 비비·시스템은 자연히 빠진다
            sql.append(" and a.actor_id = (select m.account_id from project_member m"
                    + " where m.id = :memberId and m.project_id = :projectId)");
            params.addValue("memberId", memberId);
        }
        if (agentSessionId != null) {
            sql.append(" and a.agent_session_id = :agentSessionId");
            params.addValue("agentSessionId", agentSessionId);
        }
        if (changeProposalId != null) {
            sql.append(" and a.change_proposal_id = :changeProposalId");
            params.addValue("changeProposalId", changeProposalId);
        }
        return sql.toString();
    }

    private static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }
}
