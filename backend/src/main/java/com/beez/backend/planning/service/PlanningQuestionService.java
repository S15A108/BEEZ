package com.beez.backend.planning.service;

import static com.beez.backend.designitem.support.DesignJson.JSON;
import static com.beez.backend.designitem.support.DesignJson.invalid;
import static com.beez.backend.designitem.support.DesignJson.time;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.planning.dto.AnswerRequest;
import com.beez.backend.planning.repository.PlanningQuestionRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * PLN-A03 기획 보완 질문 목록과 PLN-A04 답변. 질문은 문서 분석이 끝난 뒤 {@link PlanningQuestionGenerator} 가 만든다.
 * 답변은 질문 행에만 쓰고 설계 항목은 건드리지 않는다("답변 자체가 승인 설계 변경은 아니다"). 같은 질문에 다시 보내면 덮어쓴다.
 */
@Service
public class PlanningQuestionService {
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    private final ProjectAccessChecker access;
    private final LeaderGuard leader;
    private final PlanningQuestionRepository questions;

    public PlanningQuestionService(ProjectAccessChecker access, LeaderGuard leader, PlanningQuestionRepository questions) {
        this.access = access; this.leader = leader; this.questions = questions;
    }

    /** 목록과 전체 수가 같은 시점의 값이어야 해서 읽기 전용 반복 가능 읽기로 묶는다. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> list(CallerContext caller, long project, Long version, String cursor, Integer limit) {
        access.requireMember(caller, project);
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) throw invalid("limit은 1~" + MAX_LIMIT + "입니다.");
        var after = cursor == null || cursor.isBlank() ? null : position(cursor.trim());
        var rows = questions.page(project, version, after, size + 1);
        boolean more = rows.size() > size;
        var items = new ArrayList<Map<String, Object>>();
        for (var row : rows.subList(0, Math.min(size, rows.size()))) items.add(view(row));
        var response = new LinkedHashMap<String, Object>();
        response.put("items", items);
        response.put("total_count", questions.count(project, version));
        response.put("has_more", more);
        if (more) response.put("next_cursor", cursorOf(rows.get(size - 1)));
        return response;
    }

    /** 커서는 마지막 질문의 id 다. 번호나 rank 가 아니라서 쪽 사이에 목록이 바뀌거나 재분석이 rank 를 고쳐도 겹치거나 빠지지 않는다. */
    static String cursorOf(Map<String, Object> row) {
        return String.valueOf(((Number) row.get("id")).longValue());
    }

    private static PlanningQuestionRepository.Position position(String cursor) {
        try {
            long id = Long.parseLong(cursor);
            if (id < 0) throw invalid("cursor가 올바르지 않습니다.");
            return new PlanningQuestionRepository.Position(id);
        } catch (NumberFormatException e) {
            throw invalid("cursor가 올바르지 않습니다.");
        }
    }

    @Transactional
    public Map<String, Object> answer(CallerContext caller, long project, long questionId, AnswerRequest input) {
        if (input == null) throw invalid("요청 본문이 필요합니다.");
        leader.lock(caller, project);
        var row = questions.findForUpdate(project, questionId).orElseThrow(() -> AccessErrors.notFound("planning_question_missing"));
        if (questions.hasBaseline(project)) {
            throw new ApiException(HttpStatus.CONFLICT, "design_already_published", "이미 발행된 초안입니다.");
        }
        // 현재 버전이 아닌 버전의 미답변 질문은 현재 버전 질문이 다 만들어지면 조회할 때 대체로 판정된다(S15P31A108-396).
        if ("SUPERSEDED".equals(row.get("status")) || "SUPERSEDED".equals(row.get("freshness"))) {
            throw new ApiException(HttpStatus.CONFLICT, "question_superseded",
                    "문서가 다시 분석되어 이 질문은 더 이상 쓰이지 않아요. 목록을 새로 불러와 주세요.");
        }
        // 이전 본문으로 만든 미답변 질문은 새 본문으로 다시 만드는 중이거나 그러지 못한 상태다 — 답하거나 닫지 않는다.
        // 이미 답한·닫은 질문은 이력이라 그대로 두고 다시 고칠 수도 있다(S15P31A108-396).
        if ("OPEN".equals(row.get("status")) && !"CURRENT".equals(row.get("freshness"))) {
            throw new ApiException(HttpStatus.CONFLICT, "question_outdated", "REFRESH_FAILED".equals(row.get("freshness"))
                    ? "이 질문은 이전 본문으로 만든 거라 답할 수 없어요. 질문을 다시 만든 뒤 답해 주세요."
                    : "문서가 바뀌어 질문을 새 본문으로 다시 만드는 중이에요. 잠시 뒤 목록을 새로 불러와 주세요.");
        }
        if ("DISMISSED".equals(input.status())) {
            if (input.answer() != null || input.answerPayload() != null) throw invalid("닫는 요청에는 답변을 함께 보낼 수 없습니다.");
            questions.dismiss(questionId);
        } else if (input.status() != null) {
            throw invalid("status는 DISMISSED만 보낼 수 있습니다.");
        } else {
            var normalized = PlanningAnswers.normalize((String) row.get("question_type"), format(row), input.answer(), input.answerPayload());
            questions.answer(questionId, normalized.display(), normalized.payload(), caller.accountId());
        }
        return view(questions.find(project, questionId).orElseThrow());
    }

    private static Map<String, Object> format(Map<String, Object> row) {
        return row.get("answer_format") == null ? Map.of() : json(row.get("answer_format"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(Object value) { return JSON.readValue(value.toString(), Map.class); }

    static Map<String, Object> view(Map<String, Object> row) {
        var view = new LinkedHashMap<String, Object>();
        view.put("id", row.get("id").toString());
        if (row.get("number") != null) view.put("number", ((Number) row.get("number")).longValue());
        view.put("question", row.get("question"));
        view.put("kind", row.get("kind"));
        if (row.get("question_type") != null) {
            view.put("question_type", row.get("question_type"));
            view.put("answer_format", json(row.get("answer_format")));
        }
        view.put("status", row.get("status"));
        if (row.get("freshness") != null) view.put("freshness", row.get("freshness"));
        if (row.get("evidence_status") != null) view.put("evidence_status", row.get("evidence_status"));
        if (row.get("summary") != null) view.put("summary", row.get("summary"));
        Map<String, Object> source = source(row);
        if (source != null) view.put("source", source);
        if (row.get("answer") != null) view.put("answer", row.get("answer"));
        if (row.get("answer_payload") != null) view.put("answer_payload", json(row.get("answer_payload")));
        if (row.get("answered_by") != null) view.put("answered_by", row.get("answered_by").toString());
        if (row.get("answered_at") != null) view.put("answered_at", time(row.get("answered_at")));
        return view;
    }

    /** 응답 {@code source} 는 첫 근거의 위치다. 근거가 없으면 문서 버전만 알려 준다. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> source(Map<String, Object> row) {
        if (row.get("document_version_id") == null || row.get("document_id") == null) return null;
        var source = new LinkedHashMap<String, Object>();
        source.put("document_id", row.get("document_id").toString());
        source.put("document_version_id", row.get("document_version_id").toString());
        source.put("version_no", ((Number) row.get("version_no")).intValue());
        String location = "원문 전체";
        if (row.get("evidence") != null) {
            List<Object> evidence = JSON.readValue(row.get("evidence").toString(), List.class);
            if (!evidence.isEmpty() && evidence.get(0) instanceof Map<?, ?> first
                    && first.get("location") instanceof String s && !s.isBlank()) {
                location = s;
            }
        }
        source.put("location", location);
        return source;
    }
}
