package com.beez.backend.planning.service;

import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.planning.dto.DraftDtos.AdoptRequest;
import com.beez.backend.planning.dto.DraftDtos.BatchItem;
import com.beez.backend.planning.dto.DraftDtos.BatchRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 생성 후보를 여러 개 골라 한 번에 채택·폐기한다(S15P31A108-389). 건마다 {@link DesignDraftService#adopt} / {@code discard} 를
 * <b>자기 트랜잭션으로</b> 부르므로(이 클래스는 트랜잭션을 열지 않는다 — 열면 한 건 실패가 전체를 되돌린다) 일부가 실패해도
 * 나머지는 처리되고 건별 결과가 돌아온다. 규칙은 단건과 같다: 근거 없는 후보 확인, 충돌 필드 확정, 후보를 만든 뒤 바뀌었는지 재확인.
 */
@Service
public class DesignDraftAdoptionService {
    private static final Logger log = LoggerFactory.getLogger(DesignDraftAdoptionService.class);
    static final int MAX_BATCH = 50;

    private final ProjectAccessChecker access;
    private final DesignDraftService drafts;

    public DesignDraftAdoptionService(ProjectAccessChecker access, DesignDraftService drafts) {
        this.access = access; this.drafts = drafts;
    }

    public Map<String, Object> adopt(CallerContext caller, long project, BatchRequest input) {
        var items = validate(caller, project, input);
        var results = new ArrayList<Map<String, Object>>();
        int adopted = 0;
        for (var item : items) {
            var result = new LinkedHashMap<String, Object>();
            result.put("candidate_id", item.candidateId());
            try {
                var view = drafts.adopt(caller, project, Long.parseLong(item.candidateId()), new AdoptRequest(item.confirmNoEvidence(), item.resolvedFields()));
                result.put("result", "ADOPTED");
                result.put("design_item_version_id", view.get("design_item_version_id"));
                adopted++;
            } catch (ApiException e) {
                failed(result, e.getCode(), e.getMessage());
            } catch (RuntimeException e) {
                log.error("후보 일괄 채택 중 예기치 않은 오류 candidate={} 예외 종류={}", item.candidateId(), e.getClass().getSimpleName());
                failed(result, "internal_error", "후보를 채택하지 못했습니다. 잠시 뒤 다시 시도해 주세요.");
            }
            results.add(result);
        }
        return summary(results, adopted);
    }

    public Map<String, Object> discard(CallerContext caller, long project, BatchRequest input) {
        var items = validate(caller, project, input);
        var results = new ArrayList<Map<String, Object>>();
        int done = 0;
        for (var item : items) {
            var result = new LinkedHashMap<String, Object>();
            result.put("candidate_id", item.candidateId());
            try {
                drafts.discard(caller, project, Long.parseLong(item.candidateId()));
                result.put("result", "DISCARDED");
                done++;
            } catch (ApiException e) {
                failed(result, e.getCode(), e.getMessage());
            } catch (RuntimeException e) {
                log.error("후보 일괄 폐기 중 예기치 않은 오류 candidate={} 예외 종류={}", item.candidateId(), e.getClass().getSimpleName());
                failed(result, "internal_error", "후보를 폐기하지 못했습니다. 잠시 뒤 다시 시도해 주세요.");
            }
            results.add(result);
        }
        return summary(results, done);
    }

    /** 요청 전체가 거절되는 경우: 팀장이 아님, 빈 목록, 너무 많음, 잘못되거나 겹친 id. */
    private List<BatchItem> validate(CallerContext caller, long project, BatchRequest input) {
        access.requireHumanLeader(caller, project);
        if (input == null || input.items() == null || input.items().isEmpty() || input.items().size() > MAX_BATCH) {
            throw invalid("items는 1~" + MAX_BATCH + "개여야 합니다.");
        }
        var seen = new LinkedHashSet<Long>();
        for (var item : input.items()) {
            long id;
            try { id = Long.parseLong(item == null || item.candidateId() == null ? "" : item.candidateId().trim()); }
            catch (NumberFormatException e) { throw invalid("candidate_id가 올바르지 않습니다."); }
            if (id < 1 || !seen.add(id)) throw invalid("candidate_id에 중복이거나 잘못된 값이 있습니다.");
        }
        return input.items().stream().map(i -> new BatchItem(i.candidateId().trim(), i.confirmNoEvidence(), i.resolvedFields())).toList();
    }

    private static void failed(Map<String, Object> result, String code, String message) {
        result.put("result", "FAILED");
        result.put("error_code", code);
        result.put("message", message);
    }

    private static Map<String, Object> summary(List<Map<String, Object>> results, int succeeded) {
        var view = new LinkedHashMap<String, Object>();
        view.put("results", results);
        view.put("succeeded", succeeded);
        view.put("failed", results.size() - succeeded);
        return view;
    }

    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", message); }
}
