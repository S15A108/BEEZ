package com.beez.backend.meeting.service;

import com.beez.backend.common.api.Ids;
import com.beez.backend.designitem.support.DesignJson;
import com.beez.backend.meeting.dto.MeetingEntryResponse;
import com.beez.backend.meeting.dto.MeetingRef;
import com.beez.backend.meeting.dto.MeetingResponse;
import com.beez.backend.meeting.dto.ParticipantResponse;
import com.beez.backend.meeting.repository.MeetingRepository;
import com.beez.backend.meeting.repository.MeetingRepository.EntryRow;
import com.beez.backend.meeting.repository.MeetingRepository.MeetingRow;
import com.beez.backend.meeting.repository.MeetingRepository.ParticipantRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

/** 회의·기록 행을 응답으로 바꾼다. 작업·참여자는 페이지 단위로 한 번에 읽는다 */
@Component
class MeetingAssembler {

    private final MeetingRepository meetings;

    MeetingAssembler(MeetingRepository meetings) {
        this.meetings = meetings;
    }

    List<MeetingResponse> toResponses(long projectId, List<MeetingRow> rows) {
        List<Long> ids = rows.stream().map(MeetingRow::id).toList();
        Map<Long, List<Long>> tasks = meetings.taskIds(ids);
        Map<Long, List<ParticipantRow>> participants = meetings.participants(projectId, ids);
        List<MeetingResponse> out = new ArrayList<>();
        for (MeetingRow row : rows) {
            List<ParticipantResponse> people = new ArrayList<>();
            for (ParticipantRow p : participants.getOrDefault(row.id(), List.of())) {
                people.add(new ParticipantResponse(Ids.str(p.memberId()), p.status(), p.role(), Ids.str(p.addedByMemberId())));
            }
            out.add(new MeetingResponse(Ids.str(row.id()), Ids.str(row.projectId()), row.title(),
                    Ids.str(row.changeProposalId()), row.origin(), Ids.strs(tasks.getOrDefault(row.id(), List.of())),
                    row.conflictSummary(), row.status(), row.closedAt(), Ids.str(row.closedByAccountId()), people,
                    row.createdAt()));
        }
        return out;
    }

    MeetingResponse toResponse(long projectId, MeetingRow row) {
        return toResponses(projectId, List.of(row)).get(0);
    }

    static MeetingEntryResponse toResponse(EntryRow row) {
        List<MeetingRef> refs = row.refs() == null ? null
                : DesignJson.JSON.readValue(row.refs(), new TypeReference<List<MeetingRef>>() { });
        return new MeetingEntryResponse(Ids.str(row.id()), Ids.str(row.meetingId()), row.kind(), row.body(),
                row.authorKind(), Ids.str(row.authorAccountId()), Ids.str(row.authorMemberId()), row.authorName(),
                refs, row.createdAt());
    }
}
