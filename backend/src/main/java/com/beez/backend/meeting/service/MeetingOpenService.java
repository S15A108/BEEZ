package com.beez.backend.meeting.service;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.designitem.support.DesignJson;
import com.beez.backend.meeting.dto.MeetingRef;
import com.beez.backend.meeting.dto.MeetingResponse;
import com.beez.backend.meeting.repository.MeetingRepository;
import com.beez.backend.meeting.repository.MeetingRepository.MeetingRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작업 충돌 회의 내부 개설 (S15P31A108-205). HTTP 로 노출하지 않는다 — 사람이 회의를 만드는 API 는 없다.
 * 충돌 감지(변준성 검증)가 이 서비스를 호출하는 연결은 후속 티켓이고, 여기서는 개설 규칙만 맡는다.
 * 호출하는 쪽의 트랜잭션에 참여한다(단독 호출도 가능).
 */
@Service
public class MeetingOpenService {

    public static final String ROLE_LEADER = "LEADER";
    public static final String ROLE_TASK_OWNER = "TASK_OWNER";
    public static final String ROLE_DOMAIN_OWNER = "DOMAIN_OWNER";

    private static final int TITLE_MAX = 80;
    private static final String DEFAULT_TITLE = "작업 충돌 협의";

    /** 호출자가 정한 추가 참여자. role 은 TASK_OWNER 또는 DOMAIN_OWNER */
    public record ParticipantSeed(long memberId, String role) {
    }

    /** extraParticipants 는 충돌한 설계 항목의 담당 영역 팀원처럼 호출자만 아는 대상. 이 서비스는 계산하지 않는다 */
    public record ConflictMeetingCommand(long projectId, List<Long> taskIds, String conflictSummary, String briefText,
                                         List<MeetingRef> briefRefs, List<ParticipantSeed> extraParticipants) {
    }

    public record OpenResult(boolean created, MeetingResponse meeting) {
    }

    private final MeetingRepository meetings;
    private final MeetingAssembler assembler;

    public MeetingOpenService(MeetingRepository meetings, MeetingAssembler assembler) {
        this.meetings = meetings;
        this.assembler = assembler;
    }

    /**
     * 같은 작업 묶음의 열린 회의가 있으면 새로 만들지 않고 그 회의를 created=false 로 돌려준다(멱등).
     * 참여자는 팀장 + 작업 담당자(담당자 없는 작업은 건너뜀) + 호출자가 넘긴 활성 멤버. 첫 기록은 비비의 CONFLICT_BRIEF.
     */
    @Transactional
    public OpenResult openForConflict(ConflictMeetingCommand command) {
        long projectId = command.projectId();
        if (!meetings.hasBaseline(projectId)) {
            throw new ApiException(HttpStatus.CONFLICT, "baseline_missing", "승인된 기준 설계가 아직 없습니다");
        }
        if (command.taskIds() == null || command.taskIds().isEmpty()) {
            throw invalid("충돌한 작업이 필요합니다");
        }
        TreeSet<Long> taskIds = new TreeSet<>(command.taskIds());
        Map<Long, Long> owners = meetings.taskOwnerAccounts(projectId, taskIds);
        if (owners.size() != taskIds.size()) {
            throw invalid("이 프로젝트의 작업만 지정할 수 있습니다");
        }
        String key = taskIds.stream().map(String::valueOf).collect(Collectors.joining(","));

        Optional<MeetingRow> existing = meetings.findOpenByTaskSet(projectId, key);
        if (existing.isPresent()) {
            return new OpenResult(false, assembler.toResponse(projectId, existing.get()));
        }

        String summary = command.conflictSummary() == null || command.conflictSummary().isBlank()
                ? null : command.conflictSummary().strip();
        Optional<Long> id = meetings.insertMeeting(projectId, title(summary), summary, key, new ArrayList<>(taskIds));
        if (id.isEmpty()) {
            // 동시에 같은 묶음을 연 요청이 먼저 커밋했다
            MeetingRow winner = meetings.findOpenByTaskSet(projectId, key).orElseThrow();
            return new OpenResult(false, assembler.toResponse(projectId, winner));
        }
        long meetingId = id.get();

        // 같은 사람은 한 번만: 팀장 → 작업 담당자 → 호출자가 넘긴 대상 순으로 먼저 정해진 역할을 둔다
        Map<Long, String> roles = new LinkedHashMap<>();
        meetings.activeLeaderAccount(projectId).ifPresent(a -> roles.put(a, ROLE_LEADER));
        owners.values().stream().filter(a -> a != null).forEach(a -> roles.putIfAbsent(a, ROLE_TASK_OWNER));
        if (command.extraParticipants() != null) {
            List<Long> memberIds = command.extraParticipants().stream().map(ParticipantSeed::memberId).toList();
            Map<Long, Long> accounts = meetings.activeAccountsByMember(projectId, memberIds);
            for (ParticipantSeed seed : command.extraParticipants()) {
                Long account = accounts.get(seed.memberId());
                if (account != null) {
                    roles.putIfAbsent(account, seed.role());
                }
            }
        }
        roles.forEach((account, role) -> meetings.addParticipant(meetingId, account, role, null));

        String brief = command.briefText() == null || command.briefText().isBlank()
                ? (summary == null ? DEFAULT_TITLE : summary) : command.briefText().strip();
        String refs = command.briefRefs() == null || command.briefRefs().isEmpty()
                ? null : DesignJson.JSON.writeValueAsString(command.briefRefs());
        meetings.insertEntry(meetingId, "CONFLICT_BRIEF", "BEE", null, brief, refs);

        // TODO(알림 공통 서비스, S15P31A108-237): 참여자에게 MEETING_OPENED 알림·활동을 전달한다.
        //  이미 열린 회의를 돌려주는 위의 경로에서는 전달하지 않는다.
        return new OpenResult(true, assembler.toResponse(projectId, meetings.findMeeting(projectId, meetingId).orElseThrow()));
    }

    private static String title(String summary) {
        if (summary == null) {
            return DEFAULT_TITLE;
        }
        return summary.length() <= TITLE_MAX ? summary : summary.substring(0, TITLE_MAX);
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", message);
    }
}
