package com.beez.backend.meeting.service;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.api.PageResponse;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.designitem.service.DesignCommandService;
import com.beez.backend.meeting.dto.AddParticipantsRequest;
import com.beez.backend.meeting.dto.CreateMeetingEntryRequest;
import com.beez.backend.meeting.dto.MeetingEntryResponse;
import com.beez.backend.meeting.dto.MeetingResponse;
import com.beez.backend.meeting.repository.MeetingRepository;
import com.beez.backend.meeting.repository.MeetingRepository.EntryRow;
import com.beez.backend.meeting.repository.MeetingRepository.MeetingRow;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MTG-A02·A03·A04·A06·A10·A14 회의 조회·참여자·기록 (S15P31A108-205).
 * 모든 API 는 프로젝트 멤버 확인 뒤 기준 v1 이 있어야 열린다(409 baseline_missing) — 비멤버에게 상태를 드러내지 않는다.
 * 참여자 변경·기록 저장은 회의 행 잠금으로 직렬화한다. 다른 프로젝트 회의는 없는 것과 같이 404.
 */
@Service
public class MeetingService {

    private static final int MAX_ENTRY_TEXT = 4000;
    private static final int MAX_ADD_MEMBERS = 50;

    private final ProjectAccessChecker access;
    private final MeetingRepository meetings;
    private final MeetingAssembler assembler;
    private final DesignCommandService idempotency;

    public MeetingService(ProjectAccessChecker access, MeetingRepository meetings, MeetingAssembler assembler,
                          DesignCommandService idempotency) {
        this.access = access;
        this.meetings = meetings;
        this.assembler = assembler;
        this.idempotency = idempotency;
    }

    /** MTG-A02. id 내림차순(최근 회의 먼저) 커서 목록 */
    @Transactional(readOnly = true)
    public PageResponse<MeetingResponse> list(CallerContext caller, long projectId, String status, String cursor,
                                              Integer limit) {
        access.requireMember(caller, projectId);
        requireBaseline(projectId);
        if (status != null && !status.equals("OPEN") && !status.equals("CLOSED")) {
            throw invalid("status 는 OPEN 또는 CLOSED 만 가능합니다");
        }
        int size = PageResponse.normalizeLimit(limit);
        long before = PageResponse.beforeId(cursor);
        List<MeetingRow> rows = meetings.list(projectId, status, before, size + 1);
        return PageResponse.fromRows(rows, size, MeetingRow::id, page -> assembler.toResponses(projectId, page));
    }

    /** MTG-A03 */
    @Transactional(readOnly = true)
    public MeetingResponse get(CallerContext caller, long projectId, String meetingIdRaw) {
        access.requireMember(caller, projectId);
        requireBaseline(projectId);
        long meetingId = Ids.parse(meetingIdRaw, "meeting_id");
        return assembler.toResponse(projectId, meetings.findMeeting(projectId, meetingId)
                .orElseThrow(() -> missing(projectId, meetingId)));
    }

    /** MTG-A04. 팀장 또는 이 회의 참여자(사람)가 활성 멤버를 JOINED 로 추가한다. 이미 참여 중이면 건너뛴다 */
    @Transactional
    public MeetingResponse addParticipants(CallerContext caller, long projectId, String meetingIdRaw,
                                           AddParticipantsRequest body) {
        ProjectActor actor = access.requireMember(caller, projectId);
        actor.requireHuman();
        requireBaseline(projectId);
        long meetingId = Ids.parse(meetingIdRaw, "meeting_id");
        MeetingRow meeting = meetings.lockMeeting(projectId, meetingId).orElseThrow(() -> missing(projectId, meetingId));
        if (!actor.isLeader() && !meetings.isParticipant(meetingId, actor.accountId())) {
            throw AccessErrors.forbidden("not_meeting_participant meeting=" + meetingId + " member=" + actor.memberId(),
                    "팀장이나 회의 참여자만 참여자를 추가할 수 있습니다");
        }
        requireOpen(meeting);

        if (body == null || body.memberIds() == null || body.memberIds().isEmpty()) {
            throw invalid("추가할 멤버가 필요합니다");
        }
        if (body.memberIds().size() > MAX_ADD_MEMBERS) {
            throw invalid("한 번에 " + MAX_ADD_MEMBERS + "명까지 추가할 수 있습니다");
        }
        Set<Long> memberIds = new LinkedHashSet<>(Ids.parseAll(body.memberIds(), "member_ids"));
        Map<Long, Long> accounts = meetings.activeAccountsByMember(projectId, memberIds);
        if (accounts.size() != memberIds.size()) {
            throw invalid("이 프로젝트의 활성 멤버만 추가할 수 있습니다");
        }
        // 멤버 번호 순서대로 넣어 같은 입력이 같은 결과를 만든다. 이미 참여 중이면 건너뛴다
        List<Long> added = new ArrayList<>();
        for (Long memberId : memberIds) {
            long account = accounts.get(memberId);
            if (meetings.addParticipant(meetingId, account, null, actor.accountId()) == 1) {
                added.add(memberId);
            }
        }
        // TODO(알림 공통 서비스, S15P31A108-237): added 의 멤버에게 target_type=MEETING 알림(reason 포함)과 활동을 전달한다.
        //  새로 추가된 사람이 없으면 보내지 않는다.
        return assembler.toResponse(projectId, meeting);
    }

    /** MTG-A14. 팀장만. 이미 빠졌거나 참여자가 아니면 204(멱등) */
    @Transactional
    public void removeParticipant(CallerContext caller, long projectId, String meetingIdRaw, String memberIdRaw) {
        ProjectActor actor = access.requireHumanLeader(caller, projectId);
        requireBaseline(projectId);
        long meetingId = Ids.parse(meetingIdRaw, "meeting_id");
        long memberId = Ids.parse(memberIdRaw, "member_id");
        MeetingRow meeting = meetings.lockMeeting(projectId, meetingId).orElseThrow(() -> missing(projectId, meetingId));
        // 잠금을 기다리는 동안 팀장이 바뀌었으면 이전 팀장의 요청은 여기서 403
        access.requireLeaderAfterLock(actor);
        requireOpen(meeting);

        long account = meetings.memberAccount(projectId, memberId)
                .orElseThrow(() -> AccessErrors.notFound("meeting_member_missing project=" + projectId + " member=" + memberId));
        if (meetings.activeLeaderAccount(projectId).filter(leader -> leader == account).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "leader_not_removable", "팀장은 회의에서 뺄 수 없습니다");
        }
        if (meetings.removeParticipant(meetingId, account) == 0) {
            return;
        }
        // TODO(알림 공통 서비스, S15P31A108-237): 빠진 사람에게 누가 뺐는지 담아 target_type=MEETING 알림과 활동을 전달한다.
        //  위에서 지운 행이 없으면 보내지 않는다(멱등).
    }

    /**
     * MTG-A06. 회의 참여자(사람)가 텍스트 기록을 남긴다. Idempotency-Key 는 선택이며 있으면 24시간 재생한다:
     * 참여 확인 뒤, 끝난 회의 검사보다 먼저 같은 키·입력의 처음 응답을 돌려주고 다른 입력은 409 idempotency_conflict.
     */
    @Transactional
    public MeetingEntryResponse createEntry(CallerContext caller, long projectId, String meetingIdRaw,
                                            CreateMeetingEntryRequest body, String idempotencyKey) {
        ProjectActor actor = access.requireMember(caller, projectId);
        actor.requireHuman();
        requireBaseline(projectId);
        long meetingId = Ids.parse(meetingIdRaw, "meeting_id");
        MeetingRow meeting = meetings.lockMeeting(projectId, meetingId).orElseThrow(() -> missing(projectId, meetingId));
        if (!meetings.isParticipant(meetingId, actor.accountId())) {
            throw AccessErrors.forbidden("not_meeting_participant meeting=" + meetingId + " member=" + actor.memberId(),
                    "회의 참여자만 기록을 남길 수 있습니다");
        }
        if (body == null || body.text() == null || body.text().isBlank()) {
            throw invalid("기록 본문이 필요합니다");
        }
        if (body.text().length() > MAX_ENTRY_TEXT) {
            throw invalid("기록 본문은 " + MAX_ENTRY_TEXT + "자 이하여야 합니다");
        }
        String text = body.text().strip();
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.strip();
        String operation = "meeting:entry:" + meetingId;
        Input input = new Input(text);
        if (key != null) {
            meetings.lockIdempotency(meetingId, actor.accountId(), key);
            Optional<MeetingEntryResponse> replay = idempotency.replay(projectId, actor.accountId(), operation, key,
                    input, MeetingEntryResponse.class);
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        requireOpen(meeting);

        long id = meetings.insertEntry(meetingId, "TEXT", "HUMAN", actor.accountId(), text, null);
        MeetingEntryResponse response = MeetingAssembler.toResponse(meetings.findEntry(projectId, id).orElseThrow());
        if (key != null) {
            idempotency.save(projectId, actor.accountId(), operation, key, input, response);
        }
        return response;
    }

    /**
     * MTG-A10. 작성 순서(오래된 순) 커서 목록. cursor 는 이전 페이지 마지막 기록 ID.
     * after_entry_id 가 있으면 그 뒤 기록만 오래된 순으로 돌려준다(폴링) — cursor 와 함께 오면 더 큰 쪽 뒤부터.
     */
    @Transactional(readOnly = true)
    public PageResponse<MeetingEntryResponse> listEntries(CallerContext caller, long projectId, String meetingIdRaw,
                                                          String cursor, Integer limit, String afterEntryId) {
        access.requireMember(caller, projectId);
        requireBaseline(projectId);
        long meetingId = Ids.parse(meetingIdRaw, "meeting_id");
        meetings.findMeeting(projectId, meetingId).orElseThrow(() -> missing(projectId, meetingId));
        int size = PageResponse.normalizeLimit(limit);
        long after = Math.max(cursor == null || cursor.isBlank() ? 0L : Ids.parse(cursor, "cursor"),
                afterEntryId == null || afterEntryId.isBlank() ? 0L : Ids.parse(afterEntryId, "after_entry_id"));
        List<EntryRow> rows = meetings.listEntries(projectId, meetingId, after, size + 1);
        return PageResponse.fromRows(rows, size, EntryRow::id, page -> {
            List<MeetingEntryResponse> items = new ArrayList<>();
            for (EntryRow row : page) {
                items.add(MeetingAssembler.toResponse(row));
            }
            return items;
        });
    }

    private void requireBaseline(long projectId) {
        if (!meetings.hasBaseline(projectId)) {
            throw new ApiException(HttpStatus.CONFLICT, "baseline_missing", "승인된 기준 설계가 아직 없습니다");
        }
    }

    private void requireOpen(MeetingRow meeting) {
        if (!"OPEN".equals(meeting.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "meeting_closed", "끝난 회의입니다");
        }
    }

    private ApiException missing(long projectId, long meetingId) {
        return AccessErrors.notFound("meeting_missing project=" + projectId + " meeting=" + meetingId);
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", message);
    }

    private record Input(String text) {
    }
}
