package com.beez.backend.bee.service;

import com.beez.backend.bee.dto.BeeFeedbackRequest;
import com.beez.backend.bee.dto.BeeStatusResponse;
import com.beez.backend.bee.repository.BeeRepository;
import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.auth.CallerContext;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** BEE-A03 답변 피드백, BEE-A04 현재 상태 (S15P31A108-234) */
@Service
public class BeeService {

    static final Set<String> RATINGS = Set.of("HELPFUL", "WRONG_SOURCE");
    static final int MAX_COMMENT = 1000;
    /** 이 시간이 지난 RUNNING 검토는 멈춘 것으로 보고 상태에 쓰지 않는다 */
    static final int RUNNING_REVIEW_MINUTES = 10;

    private final ProjectAccessChecker access;
    private final BeeRepository repository;

    public BeeService(ProjectAccessChecker access, BeeRepository repository) {
        this.access = access;
        this.repository = repository;
    }

    /** 같은 답변에 다시 보내면 덮어쓴다(멱등). 남의 답변·다른 프로젝트 답변은 존재를 숨기는 404 */
    @Transactional
    public void feedback(CallerContext caller, long projectId, String answerIdRaw, BeeFeedbackRequest body) {
        ProjectActor actor = access.requireMember(caller, projectId);
        actor.requireHuman();
        long answerId = Ids.parse(answerIdRaw, "answer_id");
        if (body == null || body.rating() == null || !RATINGS.contains(body.rating())) {
            throw invalid("rating 은 HELPFUL 또는 WRONG_SOURCE 여야 합니다");
        }
        String comment = body.comment() == null || body.comment().isBlank() ? null : body.comment().strip();
        if (comment != null && comment.length() > MAX_COMMENT) {
            throw invalid("comment 는 " + MAX_COMMENT + "자 이하여야 합니다");
        }
        if (!repository.upsertFeedback(projectId, answerId, actor.accountId(), body.rating(), comment)) {
            throw AccessErrors.notFound("bee_answer_missing project=" + projectId + " answer=" + answerId
                    + " account=" + actor.accountId());
        }
    }

    @Transactional(readOnly = true)
    public BeeStatusResponse status(CallerContext caller, long projectId) {
        access.requireMember(caller, projectId);
        return repository.latestActivity(projectId, RUNNING_REVIEW_MINUTES).map(a -> a.kind().equals("MEETING")
                        ? new BeeStatusResponse("MEETING", "MEETING", Ids.str(a.targetId()), "'" + a.title() + "' 진행 중")
                        : new BeeStatusResponse("DOCUMENT_REVIEW", "DOCUMENT", Ids.str(a.targetId()),
                                a.title() + " 검토 중"))
                .orElseGet(BeeStatusResponse::idle);
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", message);
    }
}
