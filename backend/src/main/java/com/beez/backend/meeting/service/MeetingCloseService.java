package com.beez.backend.meeting.service;

import static com.beez.backend.designitem.support.DesignJson.JSON;

import com.beez.backend.activity.ActivityEvent;
import com.beez.backend.activity.ActivityRecorder;
import com.beez.backend.activity.ActivityTargetType;
import com.beez.backend.changeproposal.dto.ProposalDtos;
import com.beez.backend.changeproposal.service.ProposalCommandService;
import com.beez.backend.changeproposal.validation.ProposalValidator;
import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.common.text.NormalizedText;
import com.beez.backend.designitem.service.DesignCommandService;
import com.beez.backend.meeting.dto.CloseMeetingRequest;
import com.beez.backend.meeting.dto.CloseMeetingResponse;
import com.beez.backend.meeting.dto.SummaryResult;
import com.beez.backend.meeting.repository.MeetingRepository;
import com.beez.backend.meeting.repository.MeetingRepository.MeetingRow;
import com.beez.backend.meeting.repository.MeetingRepository.OwnerRow;
import com.beez.backend.meeting.repository.MeetingSummaryRepository;
import com.beez.backend.meeting.repository.MeetingSummaryRepository.SummaryRow;
import com.beez.backend.notification.NotificationRequest;
import com.beez.backend.notification.NotificationRequester;
import com.beez.backend.notification.NotificationTargetType;
import com.beez.backend.notification.event.NotificationMessages;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MTG-A13 회의 끝내기 (S15P31A108-297). 팀장이면서 회의 참여자만 부른다. AI 를 부르지 않는다 — 문안은 정리(MTG-A11)의
 * {@code document_changes.after} 다. 문서와 기준은 종료로 바뀌지 않고 제안 승인(CHG-A09) 때만 바뀐다.
 *
 * <p>순서(원문 저장이 DB 트랜잭션 밖이라 제안 생성과 종료는 한 트랜잭션이 아니다):
 * ① 짧은 트랜잭션에서 권한·멱등 재생·열림·정리가 최신인지 확인하고 문안을 만든다
 * → ② 문서 변경이 있으면 제안을 만든다(없으면 건너뜀)
 * → ③ 짧은 트랜잭션에서 회의 행을 잠그고 ①의 조건을 다시 확인한 뒤 회의를 닫고, 알림·활동·멱등 기록을 같은 트랜잭션에 남긴다.
 *
 * <p>②는 성공했는데 ③이 실패하면 회의는 열려 있고 제안만 남는다. 다음 시도는 같은 회의의 살아 있는 제안을 만나
 * 정리가 그대로면 재사용하고, 바뀌었으면 거두고 새로 만든다({@link ProposalCommandService#createFromMeeting}).
 * ③이 정리가 오래됐다(409 summary_outdated)고 판단하면 방금 만든 제안을 거둔다(최선 노력).
 */
@Service
public class MeetingCloseService {
    private static final Logger log = LoggerFactory.getLogger(MeetingCloseService.class);
    private static final int MAX_TITLE = 200;
    private static final int MAX_REASON = 10_000;

    private final ProjectAccessChecker access;
    private final MeetingRepository meetings;
    private final MeetingSummaryRepository summaries;
    private final MeetingAssembler assembler;
    private final DesignCommandService idempotency;
    private final ProposalCommandService proposals;
    private final NotificationRequester notifier;
    private final ActivityRecorder activities;
    private final TransactionTemplate transaction;

    public MeetingCloseService(ProjectAccessChecker access, MeetingRepository meetings, MeetingSummaryRepository summaries,
                               MeetingAssembler assembler, DesignCommandService idempotency, ProposalCommandService proposals,
                               NotificationRequester notifier, ActivityRecorder activities,
                               PlatformTransactionManager manager) {
        this.access = access;
        this.meetings = meetings;
        this.summaries = summaries;
        this.assembler = assembler;
        this.idempotency = idempotency;
        this.proposals = proposals;
        this.notifier = notifier;
        this.activities = activities;
        this.transaction = new TransactionTemplate(manager);
    }

    /** 멱등 지문으로 쓰는 요청 내용 */
    private record CloseInput(long summaryId) { }

    /** ①의 결과: 멱등 재생이면 replayed, 아니면 제안으로 올릴 문안(문서 변경이 없으면 proposal 은 null) */
    private record Step(CloseMeetingResponse replayed, MeetingRow meeting, ProposalDtos.Input proposal) { }

    public CloseMeetingResponse close(CallerContext caller, long projectId, String meetingIdRaw, CloseMeetingRequest body,
                                      String key) {
        ProjectActor actor = requireLeader(caller, projectId);
        requireBaseline(projectId);
        long meetingId = Ids.parse(meetingIdRaw, "meeting_id");
        if (body == null || body.summaryId() == null || body.summaryId().isBlank()) throw invalid("summary_id 가 필요합니다");
        long summaryId = Ids.parse(body.summaryId().strip(), "summary_id");
        CloseInput input = new CloseInput(summaryId);
        String operation = "meeting:close:" + meetingId;

        Step step = transaction.execute(status -> prepare(actor, projectId, meetingId, summaryId, operation, input, key));
        if (step.replayed() != null) return step.replayed();

        ProposalDtos.Proposal proposal = null;
        if (step.proposal() != null) {
            proposal = proposals.createFromMeeting(caller, projectId, meetingId, step.proposal());
        }
        final ProposalDtos.Proposal created = proposal;
        try {
            return transaction.execute(status -> finish(caller, actor, projectId, meetingId, summaryId, operation, input, key, created));
        } catch (ApiException e) {
            // 정리가 그 사이 오래됐다: 방금 만든 제안은 쓸 곳이 없다. 다른 오류(저장 실패 등)는 제안을 남겨 다음 시도가 재사용한다
            if (created != null && "summary_outdated".equals(e.getCode())) withdrawQuietly(caller, projectId, created.id());
            throw e;
        }
    }

    // ---- ① 확인과 문안 ----

    private Step prepare(ProjectActor actor, long projectId, long meetingId, long summaryId, String operation, CloseInput input,
                         String key) {
        MeetingRow meeting = meetings.findMeeting(projectId, meetingId).orElseThrow(() -> missing(projectId, meetingId));
        requireParticipant(meetingId, actor);
        Optional<CloseMeetingResponse> replay = idempotency.replay(projectId, actor.accountId(), operation, key, input,
                CloseMeetingResponse.class);
        if (replay.isPresent()) return new Step(replay.get(), null, null);
        requireOpen(meeting);
        SummaryResult result = requireCurrent(projectId, meetingId, summaryId);
        return new Step(null, meeting, proposalInput(projectId, meeting, result));
    }

    /**
     * 정리가 지금도 유효한지: 이 회의의 최신 정리이고, 그 뒤에 사람 기록이 없고, 정리가 인용한 문서의 승인본이 지금 기준과 같다.
     * 하나라도 어긋나면 409 summary_outdated. 유효하면 정리 내용을 돌려준다.
     */
    private SummaryResult requireCurrent(long projectId, long meetingId, long summaryId) {
        SummaryRow target = summaries.find(meetingId, summaryId)
                .orElseThrow(() -> AccessErrors.notFound("meeting_summary_missing meeting=" + meetingId + " summary=" + summaryId));
        Optional<SummaryRow> latest = summaries.findLatest(meetingId);
        if (latest.isEmpty() || latest.get().id() != target.id()) {
            throw outdated("최신 정리가 아니에요. 새로 고친 뒤 다시 끝내 주세요.");
        }
        if (meetings.hasHumanEntryAfter(meetingId, target.throughEntryId())) {
            throw outdated("정리한 뒤 새 기록이 올라왔어요. 다시 정리해 주세요.");
        }
        SummaryResult result = JSON.readValue(target.result(), SummaryResult.class);
        for (SummaryResult.DocumentChange change : result.documentChanges()) {
            Optional<Long> current = summaries.baselineDocumentVersion(projectId, Long.parseLong(change.documentId()));
            if (current.isEmpty() || current.get() != Long.parseLong(change.baseVersionId())) {
                throw outdated("정리한 뒤 기준이 바뀌었어요. 다시 정리해 주세요.");
            }
        }
        return result;
    }

    /** 정리의 줄 변경을 기준 승인본에 적용한 제안 문안. 바뀔 문서가 없으면 null */
    private ProposalDtos.Input proposalInput(long projectId, MeetingRow meeting, SummaryResult result) {
        Map<Long, List<SummaryResult.DocumentChange>> byDocument = new TreeMap<>();
        for (SummaryResult.DocumentChange change : result.documentChanges()) {
            byDocument.computeIfAbsent(Long.parseLong(change.documentId()), id -> new ArrayList<>()).add(change);
        }
        if (byDocument.isEmpty()) return null;
        long baseline = summaries.currentBaseline(projectId);
        Map<Long, Map<String, Object>> bases = new LinkedHashMap<>();
        for (Map<String, Object> row : summaries.baselineDocuments(baseline, byDocument.keySet())) {
            bases.put(((Number) row.get("document_id")).longValue(), row);
        }
        List<ProposalDtos.DocumentChange> documents = new ArrayList<>();
        for (Map.Entry<Long, List<SummaryResult.DocumentChange>> entry : byDocument.entrySet()) {
            Map<String, Object> base = bases.get(entry.getKey());
            if (base == null) throw outdated("정리한 뒤 기준이 바뀌었어요. 다시 정리해 주세요.");
            String baseText = (String) base.get("content_text");
            List<DocumentLineEditor.LineChange> edits = entry.getValue().stream()
                    .map(c -> new DocumentLineEditor.LineChange(c.locator().startLine(), c.locator().endLine(), c.before(), c.after()))
                    .toList();
            String after;
            try {
                after = DocumentLineEditor.apply(baseText, edits);
            } catch (DocumentLineEditor.Conflict conflict) {
                throw outdated("정리한 줄이 지금 문서와 맞지 않아요. 다시 정리해 주세요.");
            }
            if (after.equals(NormalizedText.normalize(baseText))) continue; // 글자가 같으면 바뀌는 게 없다
            ProposalValidator.documentText(after); // 100,000자·1 MiB·빈 문서 — 400 validation_failed, 회의는 열어 둔다
            documents.add(new ProposalDtos.DocumentChange(Long.toString(entry.getKey()),
                    Long.toString(((Number) base.get("id")).longValue()), after, null));
        }
        if (documents.isEmpty()) return null;
        return new ProposalDtos.Input(Long.toString(baseline), title(meeting.title()), reason(result), null, null, List.of(),
                List.copyOf(documents), null, null, null);
    }

    // ---- ③ 종료 ----

    private CloseMeetingResponse finish(CallerContext caller, ProjectActor actor, long projectId, long meetingId, long summaryId,
                                        String operation, CloseInput input, String key, ProposalDtos.Proposal proposal) {
        // 다른 제안의 승인·발행이 쓰는 프로젝트 잠금을 먼저 잡는다(프로젝트 → 회의 순서). 기준 확인부터 종료까지 발행과 겹치면
        // 방금 확인한 기준이 종료 직전에 바뀌어 오래된 정리와 제안이 묶인 채 회의가 닫힐 수 있다.
        if (!meetings.lockProject(projectId)) throw missing(projectId, meetingId);
        MeetingRow meeting = meetings.lockMeeting(projectId, meetingId).orElseThrow(() -> missing(projectId, meetingId));
        // 잠금을 기다리는 동안 팀장이 바뀌었거나 회의에서 빠졌으면 여기서 403
        access.requireLeaderAfterLock(actor);
        requireParticipant(meetingId, actor);
        Optional<CloseMeetingResponse> replay = idempotency.replay(projectId, actor.accountId(), operation, key, input,
                CloseMeetingResponse.class);
        if (replay.isPresent()) return replay.get();
        requireOpen(meeting);
        requireCurrent(projectId, meetingId, summaryId); // 제안을 만드는 동안 들어온 기록·바뀐 기준을 잠근 상태에서 다시 본다
        // 이번 정리에 문서 변경이 없으면 제안을 새로 만들지 않는다. 앞선 시도가 남긴 제안이 있으면 같이 거둬 제안 없이 닫힌 회의에
        // 승인될 수 있는 제안이 남지 않게 한다(회의 종료와 같은 트랜잭션).
        if (proposal == null) proposals.withdrawLiveMeetingProposal(caller, projectId, meetingId);

        Long proposalId = proposal == null ? null : Long.parseLong(proposal.id());
        meetings.close(meetingId, actor.accountId(), proposalId);
        List<String> notified = proposal == null ? List.of() : notifyOwners(actor, meeting, proposal);
        activities.record(caller, ActivityEvent.of("meeting:" + meetingId + ":closed", projectId, "MEETING_CLOSED",
                ActivityTargetType.MEETING, meetingId).withChangeProposalId(proposalId));

        MeetingRow closed = meetings.findMeeting(projectId, meetingId).orElseThrow();
        CloseMeetingResponse response = new CloseMeetingResponse(assembler.toResponse(projectId, closed),
                proposalId == null ? null : Long.toString(proposalId), notified);
        idempotency.save(projectId, actor.accountId(), operation, key, input, response);
        return response;
    }

    /** 회의 작업 묶음의 담당자에게(끝낸 팀장 본인은 빼고) 알린다. 받은 사람의 프로젝트 멤버 ID 를 돌려준다 */
    private List<String> notifyOwners(ProjectActor actor, MeetingRow meeting, ProposalDtos.Proposal proposal) {
        List<Long> taskIds = meetings.taskIds(List.of(meeting.id())).getOrDefault(meeting.id(), List.of());
        List<OwnerRow> owners = meetings.taskOwners(meeting.projectId(), taskIds).stream()
                .filter(owner -> owner.accountId() != actor.accountId()).toList();
        var message = NotificationMessages.meetingProposalCreated(meeting.title(), proposal.title());
        long proposalId = Long.parseLong(proposal.id());
        notifier.requestAll(owners.stream().map(owner -> new NotificationRequest(
                "meeting:" + meeting.id() + ":closed:to:" + owner.accountId(), meeting.projectId(), owner.accountId(),
                "MEETING_PROPOSAL_CREATED", message.title(), message.body(), NotificationTargetType.CHANGE_PROPOSAL,
                proposalId, null)).toList());
        return owners.stream().map(owner -> Long.toString(owner.memberId())).toList();
    }

    private void withdrawQuietly(CallerContext caller, long projectId, String proposalId) {
        try {
            proposals.withdrawMeetingProposal(caller, projectId, Long.parseLong(proposalId));
        } catch (RuntimeException e) {
            log.warn("회의 끝내기 실패 뒤 제안을 거두지 못했습니다 project={} proposal={}: {}", projectId, proposalId, e.toString());
        }
    }

    // ---- 문안 ----

    static String title(String meetingTitle) {
        String base = meetingTitle == null || meetingTitle.isBlank() ? "회의 결과" : "회의 결과: " + meetingTitle.strip();
        return base.length() <= MAX_TITLE ? base : base.substring(0, MAX_TITLE - 1) + "…";
    }

    static String reason(SummaryResult result) {
        StringBuilder text = new StringBuilder("회의에서 정리한 문서 변경을 제안합니다.");
        append(text, "충돌", result.conflicts());
        append(text, "결정", result.decisions().stream().map(SummaryResult.Decision::text).toList());
        return text.length() <= MAX_REASON ? text.toString() : text.substring(0, MAX_REASON - 1) + "…";
    }

    private static void append(StringBuilder text, String label, List<String> items) {
        if (items.isEmpty()) return;
        text.append("\n\n").append(label).append(':');
        for (String item : items) text.append("\n- ").append(item);
    }

    // ---- 확인 ----

    /** 팀장(사람)만. 팀원은 403 */
    private ProjectActor requireLeader(CallerContext caller, long projectId) {
        ProjectActor actor = access.requireMember(caller, projectId);
        actor.requireHuman();
        if (!actor.isLeader()) {
            throw AccessErrors.forbidden("role_required_leader project=" + projectId + " member=" + actor.memberId(), "팀장만 끝낼 수 있어요");
        }
        return actor;
    }

    private void requireParticipant(long meetingId, ProjectActor actor) {
        if (!meetings.isParticipant(meetingId, actor.accountId())) {
            throw AccessErrors.forbidden("not_meeting_participant meeting=" + meetingId + " member=" + actor.memberId(),
                    "회의 참여자만 끝낼 수 있어요");
        }
    }

    private void requireBaseline(long projectId) {
        if (!meetings.hasBaseline(projectId)) {
            throw new ApiException(HttpStatus.CONFLICT, "baseline_missing", "승인된 기준 설계가 아직 없습니다");
        }
    }

    private static void requireOpen(MeetingRow meeting) {
        if (!"OPEN".equals(meeting.status())) throw new ApiException(HttpStatus.CONFLICT, "meeting_closed", "끝난 회의입니다");
    }

    private static ApiException outdated(String message) {
        return new ApiException(HttpStatus.CONFLICT, "summary_outdated", message);
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", message);
    }

    private static ApiException missing(long projectId, long meetingId) {
        return AccessErrors.notFound("meeting_missing project=" + projectId + " meeting=" + meetingId);
    }
}
