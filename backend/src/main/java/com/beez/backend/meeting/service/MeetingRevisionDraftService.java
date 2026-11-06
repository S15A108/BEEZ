package com.beez.backend.meeting.service;

import static com.beez.backend.designitem.support.DesignJson.JSON;
import static com.beez.backend.designitem.support.DesignJson.hash;
import static com.beez.backend.designitem.support.DesignJson.invalid;

import com.beez.backend.changeproposal.service.ScopedDraftService;
import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.common.concurrent.SingleFlight;
import com.beez.backend.designitem.repository.DesignProjectRepository;
import com.beez.backend.designitem.service.DesignCommandService;
import com.beez.backend.meeting.dto.SummaryResult;
import com.beez.backend.meeting.repository.MeetingRepository;
import com.beez.backend.meeting.repository.MeetingRevisionDraftRepository;
import com.beez.backend.meeting.repository.MeetingSummaryRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;

/** Stores meeting-linked suggestions only; never submits or approves a proposal. */
@Service
public class MeetingRevisionDraftService {
    public record Selection(Integer decisionIndex, List<String> evidenceEntryIds) { }
    public record Request(String summaryId, String documentId, String baseDocumentVersionId,
                          List<String> selectedBlockIds, String instruction, List<Selection> decisions, Boolean confirmed) { }
    private record Prepared(Map<String,Object> meeting, List<String> agreements, long summaryId, long document, long version) { }
    private final ProjectAccessChecker access;
    private final MeetingRepository meetings;
    private final MeetingSummaryRepository summaries;
    private final MeetingRevisionDraftRepository drafts;
    private final ScopedDraftService scoped;
    private final DesignProjectRepository projects;
    private final DesignCommandService idempotency;
    private final TransactionTemplate tx;
    private final SingleFlight<Map<String,Object>> flights = new SingleFlight<>();

    public MeetingRevisionDraftService(ProjectAccessChecker access, MeetingRepository meetings, MeetingSummaryRepository summaries,
            MeetingRevisionDraftRepository drafts, ScopedDraftService scoped, DesignProjectRepository projects,
            DesignCommandService idempotency, PlatformTransactionManager manager) {
        this.access=access; this.meetings=meetings; this.summaries=summaries; this.drafts=drafts;
        this.scoped=scoped; this.projects=projects; this.idempotency=idempotency; this.tx=new TransactionTemplate(manager);
    }

    public Map<String,Object> create(CallerContext caller, long project, String meetingValue, Request input, String key) {
        ProjectActor actor = leader(caller, project);
        long meeting = Ids.parse(meetingValue,"meeting_id");
        if (input == null || !Boolean.TRUE.equals(input.confirmed()) || key == null || key.isBlank() || key.length() > 128
                || key.chars().anyMatch(Character::isISOControl)) throw invalid("결정과 근거를 확인하고 요청 키를 입력해 주세요.");
        String operation = "meeting-revision-store:" + meeting;
        return flights.run(project+"|"+actor.accountId()+"|"+operation+"|"+key, hash(input), () -> {
            Map<String,Object> replay = tx.execute(status -> {
                checkAccess(actor, project, meeting, false);
                return idempotency.replay(project, actor.accountId(), operation, key, input, Map.class).orElse(null);
            });
            if (replay != null) return replay;
            Prepared prepared = tx.execute(status -> prepare(actor, project, meeting, input));
            var req = new ScopedDraftService.ScopedRequest(input.documentId(), input.baseDocumentVersionId(), input.selectedBlockIds(),
                    input.instruction(), null, prepared.agreements(), List.of());
            Map<String,Object> output = scoped.generateMeeting(caller, project, req, key, prepared.meeting());
            return tx.execute(status -> {
                if (!projects.lockActiveProject(project)) throw AccessErrors.notFound("project_missing");
                checkAccess(actor, project, meeting, true);
                var prior = idempotency.replay(project, actor.accountId(), operation, key, input, Map.class);
                if (prior.isPresent()) return prior.get();
                Prepared current = prepare(actor, project, meeting, input);
                if (!prepared.equals(current)) throw stale();
                var payload = new LinkedHashMap<String,Object>();
                payload.put("summary_id", Long.toString(prepared.summaryId()));
                payload.put("document_id", Long.toString(prepared.document()));
                payload.put("base_document_version_id", Long.toString(prepared.version()));
                payload.put("meeting", prepared.meeting()); payload.put("draft", output);
                long id = drafts.insert(project, meeting, prepared.summaryId(), actor.accountId(), JSON.writeValueAsString(payload));
                payload.put("draft_id", Long.toString(id));
                idempotency.save(project, actor.accountId(), operation, key, input, payload);
                return payload;
            });
        });
    }

    public Map<String,Object> get(CallerContext caller, long project, String meetingValue, String draftValue) {
        access.requireMember(caller, project);
        long meeting = Ids.parse(meetingValue,"meeting_id");
        meetings.findMeeting(project,meeting).orElseThrow(() -> AccessErrors.notFound("meeting_missing"));
        Long id = draftValue == null ? null : Ids.parse(draftValue,"draft_id");
        var row = drafts.find(project,meeting,id).orElseThrow(() -> AccessErrors.notFound("meeting_revision_draft_missing"));
        Map<String,Object> result = JSON.readValue(row.payload(), new TypeReference<Map<String,Object>>() { });
        result.put("draft_id",Long.toString(row.id()));
        return result;
    }

    private Prepared prepare(ProjectActor actor, long project, long meeting, Request request) {
        checkAccess(actor,project,meeting,false);
        long summaryId=Ids.parse(request.summaryId(),"summary_id");
        long document=Ids.parse(request.documentId(),"document_id");
        long version=Ids.parse(request.baseDocumentVersionId(),"base_document_version_id");
        var summary=summaries.findLatest(meeting).orElseThrow(MeetingRevisionDraftService::stale);
        if (summary.id()!=summaryId || meetings.lastSummarizableEntryId(meeting).orElse(-1L) != summary.throughEntryId()
                || summaries.baselineDocumentVersion(project,document).orElse(-1L) != version) throw stale();
        SummaryResult result=JSON.readValue(summary.result(), SummaryResult.class);
        if (request.decisions()==null || request.decisions().isEmpty() || request.decisions().size()>30) throw invalid("확인한 결정이 필요합니다.");
        var indices=new HashSet<Integer>(); var entryIds=new LinkedHashSet<String>(); var decisions=new ArrayList<Map<String,Object>>();
        var agreements=new ArrayList<String>(); var texts=new HashSet<String>();
        for (Selection selected : request.decisions()) {
            if (selected==null || selected.decisionIndex()==null || selected.decisionIndex()<0
                    || selected.decisionIndex()>=result.decisions().size() || !indices.add(selected.decisionIndex())
                    || selected.evidenceEntryIds()==null || selected.evidenceEntryIds().isEmpty() || selected.evidenceEntryIds().size()>100)
                throw invalid("결정과 근거를 확인해 주세요.");
            String text=result.decisions().get(selected.decisionIndex()).text();
            if (text==null || text.isBlank() || !text.equals(text.strip()) || !texts.add(text)) throw invalid("결정이 비어 있거나 중복됩니다.");
            var refs=new ArrayList<String>(); var seen=new HashSet<String>();
            for (String raw : selected.evidenceEntryIds()) {
                long entry=Ids.parse(raw,"evidence_entry_id"); String ref=Long.toString(entry);
                if (!seen.add(ref) || entry>summary.throughEntryId() || !meetings.entryKind(meeting,entry).orElse("").equals("TEXT"))
                    throw invalid("근거는 이 회의의 정리 시점 이전 텍스트 기록이어야 합니다.");
                refs.add(ref); entryIds.add(ref);
            }
            agreements.add(text);
            decisions.add(Map.of("id",summaryId+":"+selected.decisionIndex(),"text",text,"evidence_entry_ids",refs));
        }
        if (entryIds.size()>200) throw invalid("근거 기록은 최대 200개입니다.");
        var entries=entryIds.stream().map(id -> Map.of("id",id,"meeting_id",Long.toString(meeting))).toList();
        var context=new LinkedHashMap<String,Object>();
        context.put("project_id",Long.toString(project)); context.put("meeting_id",Long.toString(meeting));
        context.put("document_id",Long.toString(document)); context.put("document_version_id",Long.toString(version));
        context.put("entries",entries); context.put("decisions",decisions);
        return new Prepared(context,agreements,summaryId,document,version);
    }

    private ProjectActor leader(CallerContext caller, long project) {
        ProjectActor actor=access.requireMember(caller,project); actor.requireHuman();
        if (!actor.isLeader()) throw AccessErrors.forbidden("role_required_leader","팀장만 요청할 수 있어요");
        return actor;
    }
    private void checkAccess(ProjectActor actor, long project, long meeting, boolean lock) {
        var row=(lock ? meetings.lockMeeting(project,meeting) : meetings.findMeeting(project,meeting))
                .orElseThrow(() -> AccessErrors.notFound("meeting_missing"));
        if (lock) access.requireLeaderAfterLock(actor);
        else leader(CallerContext.human(actor.accountId()),project);
        if (!meetings.isJoinedParticipant(meeting,actor.accountId())) throw AccessErrors.forbidden("not_meeting_participant","회의 참여자만 요청할 수 있어요");
        if (!"OPEN".equals(row.status())) throw new ApiException(HttpStatus.CONFLICT,"meeting_closed","끝난 회의입니다");
    }
    private static ApiException stale() { return new ApiException(HttpStatus.CONFLICT,"summary_outdated","회의 정리 또는 기준 문서가 바뀌었어요. 다시 확인해 주세요."); }
}
