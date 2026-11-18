package com.beez.backend.meeting.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.beez.backend.designitem.support.DesignJson.JSON;

import com.beez.backend.changeproposal.service.ScopedDraftService;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.designitem.repository.DesignProjectRepository;
import com.beez.backend.designitem.service.DesignCommandService;
import com.beez.backend.meeting.dto.SummaryResult;
import com.beez.backend.meeting.repository.MeetingRepository;
import com.beez.backend.meeting.repository.MeetingRevisionDraftRepository;
import com.beez.backend.meeting.repository.MeetingSummaryRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class MeetingRevisionDraftServiceTest {
    final ProjectAccessChecker access=mock(ProjectAccessChecker.class);
    final MeetingRepository meetings=mock(MeetingRepository.class);
    final MeetingSummaryRepository summaries=mock(MeetingSummaryRepository.class);
    final MeetingRevisionDraftRepository drafts=mock(MeetingRevisionDraftRepository.class);
    final ScopedDraftService scoped=mock(ScopedDraftService.class);
    final DesignProjectRepository projects=mock(DesignProjectRepository.class);
    final DesignCommandService keys=mock(DesignCommandService.class);
    final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    final ProjectActor actor=mock(ProjectActor.class);
    final CallerContext caller=CallerContext.human(5L);
    MeetingRevisionDraftService service;

    @BeforeEach void setup() {
        when(manager.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
        when(access.requireMember(any(),eq(1L))).thenReturn(actor);
        when(actor.accountId()).thenReturn(5L); when(actor.isLeader()).thenReturn(true);
        var meeting=new MeetingRepository.MeetingRow(2,1,"회의",null,"BEE_CONFLICT",null,"OPEN",null,null,Instant.now());
        when(meetings.findMeeting(1,2)).thenReturn(Optional.of(meeting));
        when(meetings.lockMeeting(1,2)).thenReturn(Optional.of(meeting));
        when(meetings.isJoinedParticipant(2,5)).thenReturn(true);
        var value=new SummaryResult(List.of(),List.of(new SummaryResult.Decision("결정 사항",null)),List.of(),List.of());
        var summary=new MeetingSummaryRepository.SummaryRow(3,2,1,11,10,null,null,JSON.writeValueAsString(value),"{}",5,Instant.now());
        when(summaries.findLatest(2)).thenReturn(Optional.of(summary));
        when(meetings.lastSummarizableEntryId(2)).thenReturn(Optional.of(10L));
        when(summaries.baselineDocumentVersion(1,4)).thenReturn(Optional.of(6L));
        when(meetings.entryKind(2,9)).thenReturn(Optional.of("TEXT"));
        when(projects.lockActiveProject(1)).thenReturn(true);
        when(drafts.insert(anyLong(),anyLong(),anyLong(),anyLong(),anyString())).thenReturn(100L);
        when(scoped.generateMeeting(any(),anyLong(),any(),anyString(),anyMap())).thenReturn(Map.of("after_content","수정"));
        service=new MeetingRevisionDraftService(access,meetings,summaries,drafts,scoped,projects,keys,manager);
    }
    MeetingRevisionDraftService.Request request(boolean confirmed, String evidence) {
        return new MeetingRevisionDraftService.Request("3","4","6",List.of("blk_"+"a".repeat(64)),"수정",
                List.of(new MeetingRevisionDraftService.Selection(0,List.of(evidence))),confirmed);
    }
    @Test void savesOnlyConfirmedDecisionAndReferences() {
        var result=service.create(caller,1,"2",request(true,"9"),"key");
        assertThat(result).containsEntry("draft_id","100");
        verify(scoped).generateMeeting(eq(caller),eq(1L),argThat(req -> req.agreements().equals(List.of("결정 사항"))),eq("key"),anyMap());
        verify(drafts).insert(eq(1L),eq(2L),eq(3L),eq(5L),contains("evidence_entry_ids"));
    }
    @Test void rejectsUnconfirmedAndUnrelatedEvidence() {
        assertThatThrownBy(() -> service.create(caller,1,"2",request(false,"9"),"key")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.create(caller,1,"2",request(true,"8"),"key")).isInstanceOf(ApiException.class);
        verifyNoInteractions(scoped,drafts);
    }
    @Test void rejectsNewMeetingEntriesDuringGeneration() {
        when(scoped.generateMeeting(any(),anyLong(),any(),anyString(),anyMap())).thenAnswer(i -> {
            when(meetings.lastSummarizableEntryId(2)).thenReturn(Optional.of(12L));
            return Map.of("after_content","수정");
        });
        assertThatThrownBy(() -> service.create(caller,1,"2",request(true,"9"),"key"))
                .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.getCode()).isEqualTo("summary_outdated"));
        verifyNoInteractions(drafts);
    }
    @Test void rejectsDocumentVersionChangedDuringGeneration() {
        when(scoped.generateMeeting(any(),anyLong(),any(),anyString(),anyMap())).thenAnswer(i -> {
            when(summaries.baselineDocumentVersion(1,4)).thenReturn(Optional.of(7L));
            return Map.of("after_content","수정");
        });
        assertThatThrownBy(() -> service.create(caller,1,"2",request(true,"9"),"key")).isInstanceOf(ApiException.class);
        verifyNoInteractions(drafts);
    }

    @Test void rejectsLeaderWhoseParticipationIsNotJoined() {
        when(meetings.isJoinedParticipant(2,5)).thenReturn(false);
        assertThatThrownBy(() -> service.create(caller,1,"2",request(true,"9"),"key")).isInstanceOf(ApiException.class);
        verifyNoInteractions(scoped,drafts);
    }
}
