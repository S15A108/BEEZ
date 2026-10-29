package com.beez.backend.changediscussion.service;

import com.beez.backend.changediscussion.dto.ChangeCommentResponse;
import com.beez.backend.changediscussion.dto.CreateChangeCommentRequest;
import com.beez.backend.changediscussion.repository.ChangeCommentRepository;
import com.beez.backend.changediscussion.repository.ChangeCommentRepository.CommentRow;
import com.beez.backend.changediscussion.repository.ChangeCommentRepository.ProposalState;
import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.api.PageResponse;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.designitem.service.DesignCommandService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CHG-A12·A13 변경 제안 의견과 검토 요청 (S15P31A108-196).
 * 의견은 협의 기록일 뿐 승인이 아니다 — DECISION 은 받지 않고 상태·기준도 바꾸지 않는다. 승인·반려의 원본은 198 의 approval.
 * 프로젝트 소속은 모든 조회 SQL 의 조건이다. 다른 프로젝트 제안은 없는 것과 같이 404.
 */
@Service
public class ChangeDiscussionService {

    public static final String COMMENT = "COMMENT";
    public static final String REVIEW_REQUEST = "REVIEW_REQUEST";

    /** 의견을 더 받지 않는 종결 상태. 조회는 모든 상태에서 된다 */
    private static final Set<String> CLOSED_STATUSES = Set.of("PUBLISHED", "WITHDRAWN");

    private static final int MAX_BODY = 4000;
    private static final int MAX_MENTIONS = 20;

    private final ProjectAccessChecker access;
    private final ChangeCommentRepository comments;
    private final DesignCommandService idempotency;

    public ChangeDiscussionService(ProjectAccessChecker access, ChangeCommentRepository comments,
                                   DesignCommandService idempotency) {
        this.access = access;
        this.comments = comments;
        this.idempotency = idempotency;
    }

    /**
     * 의견·검토 요청 등록. 대상(mentioned_member_ids)은 이 프로젝트의 현재 활성 멤버여야 하며 검토 요청은 한 명 이상 필요하다.
     * Idempotency-Key 는 선택이다. 있으면 195 와 같은 design_http_command(24시간)로 재생하며, 같은 제안·계정·키·입력은
     * 종결 상태 검사보다 먼저 처음 응답을 돌려주고 다른 입력은 409 idempotency_conflict.
     */
    @Transactional
    public ChangeCommentResponse create(CallerContext caller, long projectId, String proposalIdRaw,
                                        CreateChangeCommentRequest body, String idempotencyKey) {
        ProjectActor actor = access.requireMember(caller, projectId);
        long proposalId = Ids.parse(proposalIdRaw, "proposal_id");
        // 공유 잠금: 재제출·거두기·승인이 제안 행을 바꾸는 중이면 끝난 뒤의 상태와 리비전을 본다
        ProposalState proposal = comments.lockProposalForShare(projectId, proposalId)
                .orElseThrow(() -> missing(projectId, proposalId));

        validate(body);
        String kind = parseKind(body.kind());
        String text = body.body().strip();
        Set<Long> memberIds = new LinkedHashSet<>(Ids.parseAll(body.mentionedMemberIds(), "mentioned_member_ids"));
        if (kind.equals(REVIEW_REQUEST) && memberIds.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "검토 요청은 대상 멤버를 한 명 이상 지정해야 합니다");
        }
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.strip();
        // 정규화한 입력(종류·strip 본문·정렬한 대상). 지정 순서만 다른 재요청은 같은 입력이다
        Input input = new Input(kind, text, new ArrayList<>(new TreeSet<>(memberIds)));
        String operation = operation(caller, proposalId);
        if (key != null) {
            // 같은 키의 동시 요청을 한 줄로 세운 뒤 재생 → 저장 → 기록 순서로 처리한다
            comments.lockIdempotency(proposalId, actor.accountId(), key);
            Optional<ChangeCommentResponse> replay =
                    idempotency.replay(projectId, actor.accountId(), operation, key, input, ChangeCommentResponse.class);
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        if (CLOSED_STATUSES.contains(proposal.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "invalid_transition", "발행되었거나 거둔 변경 제안에는 의견을 남길 수 없습니다");
        }

        // 지정한 멤버가 모두 현재 활성이어야 한다. 비멤버·제외된 멤버·다른 프로젝트 멤버는 존재를 구분하지 않고 400
        Map<Long, Long> accounts = comments.activeAccountsByMember(projectId, memberIds);
        if (accounts.size() != memberIds.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "이 프로젝트의 활성 멤버만 지정할 수 있습니다");
        }

        long id = comments.insert(proposalId, proposal.revisionNo(), actor.accountId(), kind, text, accounts.values());
        ChangeCommentResponse response = ChangeCommentResponse.from(comments.findById(id).orElseThrow());
        if (key != null) {
            idempotency.save(projectId, actor.accountId(), operation, key, input, response);
        }
        // TODO(알림 공통 서비스, 전진): 저장한 의견의 지정 멤버를 알림 요청(notification_key = 의견 ID + 대상 계정)으로 전달한다.
        //  서비스 계약이 준비되기 전이라 전달하지 않는다. 멱등 재요청은 위에서 기존 응답을 돌려주므로 여기까지 오지 않는다.
        return response;
    }

    /** 작성 순서(오래된 순) 커서 목록. cursor 는 이전 페이지 마지막 의견 ID */
    @Transactional(readOnly = true)
    public PageResponse<ChangeCommentResponse> list(CallerContext caller, long projectId, String proposalIdRaw,
                                                    Integer revision, String cursor, Integer limit) {
        access.requireMember(caller, projectId);
        long proposalId = Ids.parse(proposalIdRaw, "proposal_id");
        comments.findProposal(projectId, proposalId).orElseThrow(() -> missing(projectId, proposalId));
        if (revision != null && revision < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "revision 형식이 올바르지 않습니다");
        }
        int size = PageResponse.normalizeLimit(limit);
        long after = cursor == null || cursor.isBlank() ? 0L : Ids.parse(cursor, "cursor");
        // TODO(198): 승인·반려 결정은 approval 원본을 읽어 이 목록의 결정 항목으로 합친다.
        List<CommentRow> rows = comments.list(proposalId, revision, after, size + 1);
        return PageResponse.fromRows(rows, size, CommentRow::id, page -> {
            List<ChangeCommentResponse> items = new ArrayList<>();
            for (CommentRow row : page) {
                items.add(ChangeCommentResponse.from(row));
            }
            return items;
        });
    }

    /** 다른 프로젝트 제안은 없는 것과 같이 404 */
    private ApiException missing(long projectId, long proposalId) {
        return AccessErrors.notFound("change_proposal_missing project=" + projectId + " proposal=" + proposalId);
    }

    private String parseKind(String raw) {
        if (raw == null || raw.isBlank() || raw.equals(COMMENT)) {
            return COMMENT;
        }
        if (raw.equals(REVIEW_REQUEST)) {
            return REVIEW_REQUEST;
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "kind 는 COMMENT 또는 REVIEW_REQUEST 만 가능합니다");
    }

    /** 본문 검증. Origin 검사가 먼저이도록 컨트롤러는 @Valid 를 쓰지 않고 여기서 같은 규칙을 적용한다 */
    private void validate(CreateChangeCommentRequest body) {
        if (body.body() == null || body.body().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "의견 본문이 필요합니다");
        }
        if (body.body().length() > MAX_BODY) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "의견 본문은 " + MAX_BODY + "자 이하여야 합니다");
        }
        if (body.mentionedMemberIds() != null && body.mentionedMemberIds().size() > MAX_MENTIONS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "지정 멤버는 " + MAX_MENTIONS + "명 이하여야 합니다");
        }
    }

    /** 195 와 같은 형식의 멱등 범위. 제안 ID 를 넣어 키 범위를 제안 단위로 유지한다 */
    private static String operation(CallerContext caller, long proposalId) {
        return "proposal:comment:" + proposalId + ":" + (caller.isHuman() ? "human" : "agent:" + caller.agentTokenId());
    }

    private record Input(String kind, String body, List<Long> mentionedMemberIds) {
    }
}
