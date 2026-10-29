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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    private final ProjectAccessChecker access;
    private final ChangeCommentRepository comments;

    public ChangeDiscussionService(ProjectAccessChecker access, ChangeCommentRepository comments) {
        this.access = access;
        this.comments = comments;
    }

    /**
     * 의견·검토 요청 등록. 대상(mentioned_member_ids)은 이 프로젝트의 현재 활성 멤버여야 하며 검토 요청은 한 명 이상 필요하다.
     * Idempotency-Key 가 있으면 같은 제안·작성 계정·키는 처음 저장한 의견을 돌려주고 다른 입력은 409 idempotency_conflict.
     */
    @Transactional
    public ChangeCommentResponse create(CallerContext caller, long projectId, String proposalIdRaw,
                                        CreateChangeCommentRequest body, String idempotencyKey) {
        ProjectActor actor = access.requireMember(caller, projectId);
        long proposalId = Ids.parse(proposalIdRaw, "proposal_id");
        // 공유 잠금: 재제출·거두기·승인이 제안 행을 바꾸는 중이면 끝난 뒤의 상태와 리비전을 본다
        ProposalState proposal = comments.lockProposalForShare(projectId, proposalId)
                .orElseThrow(() -> missing(projectId, proposalId));
        if (CLOSED_STATUSES.contains(proposal.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "invalid_transition", "발행되었거나 거둔 변경 제안에는 의견을 남길 수 없습니다");
        }

        String kind = parseKind(body.kind());
        String text = body.body().strip();
        Set<Long> memberIds = new LinkedHashSet<>(Ids.parseAll(body.mentionedMemberIds(), "mentioned_member_ids"));
        if (kind.equals(REVIEW_REQUEST) && memberIds.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "검토 요청은 대상 멤버를 한 명 이상 지정해야 합니다");
        }
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.strip();
        String hash = key == null ? null : requestHash(kind, text, memberIds);
        if (key != null) {
            comments.lockIdempotency(proposalId, actor.accountId(), key);
            CommentRow existing = comments.findByKey(proposalId, actor.accountId(), key).orElse(null);
            if (existing != null) {
                if (!existing.requestHash().equals(hash)) {
                    throw new ApiException(HttpStatus.CONFLICT, "idempotency_conflict", "같은 Idempotency-Key 로 다른 내용을 보낼 수 없습니다");
                }
                return ChangeCommentResponse.from(existing);
            }
        }

        // 지정한 멤버가 모두 현재 활성이어야 한다. 비멤버·제외된 멤버·다른 프로젝트 멤버는 존재를 구분하지 않고 400
        Map<Long, Long> accounts = comments.activeAccountsByMember(projectId, memberIds);
        if (accounts.size() != memberIds.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "이 프로젝트의 활성 멤버만 지정할 수 있습니다");
        }

        long id = comments.insert(proposalId, proposal.revisionNo(), actor.accountId(), kind, text, key, hash, accounts.values());
        // TODO(알림 공통 서비스, 전진): 저장한 의견의 지정 멤버를 알림 요청(notification_key = 의견 ID + 대상 계정)으로 전달한다.
        //  서비스 계약이 준비되기 전이라 전달하지 않는다. 멱등 재요청은 위에서 기존 의견을 돌려주므로 여기까지 오지 않는다.
        return ChangeCommentResponse.from(comments.findById(id).orElseThrow());
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

    /** 정규화한 입력(종류·본문·정렬한 대상)의 SHA-256. 지정 순서만 다른 재요청은 같은 입력이다 */
    private String requestHash(String kind, String text, Set<Long> memberIds) {
        String canonical = kind + "\n" + new TreeSet<>(memberIds) + "\n" + text;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
