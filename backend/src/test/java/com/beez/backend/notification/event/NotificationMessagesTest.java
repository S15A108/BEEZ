package com.beez.backend.notification.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 알림 문구는 제목 100자·본문 500자 안에서 "무슨 일이 있었다"까지만 알리고 원문을 넣지 않는다 (계약 3절). */
class NotificationMessagesTest {

    @Test
    void longNamesAreShortenedByCodePointSoTheTitleFitsTheLimit() {
        String name = "😀".repeat(60);
        var message = NotificationMessages.proposalSubmitted(name, 1);

        assertThat(message.title().codePointCount(0, message.title().length())).isLessThanOrEqualTo(100);
        assertThat(message.title()).endsWith("…").doesNotContain("�");
    }

    @Test
    void blankNamesFallBackToAPlaceholder() {
        assertThat(NotificationMessages.documentApproved("  ").title()).contains("(이름 없음)");
        assertThat(NotificationMessages.proposalRejected(null).title()).contains("(이름 없음)");
    }

    @Test
    void resubmissionIsWordedAsASupplement() {
        assertThat(NotificationMessages.proposalSubmitted("제안", 2).body()).contains("보완");
        assertThat(NotificationMessages.proposalSubmitted("제안", 1).body()).doesNotContain("보완");
    }

    @Test
    void meetingReasonIsIncludedOnlyWhenGiven() {
        assertThat(NotificationMessages.meetingParticipantAdded("회의", "  로그인 충돌 논의  ").body())
                .contains("이유: 로그인 충돌 논의");
        assertThat(NotificationMessages.meetingParticipantAdded("회의", " ").body()).doesNotContain("이유");
        assertThat(NotificationMessages.meetingParticipantAdded("회의", null).body()).doesNotContain("이유");
    }

    @Test
    void everyMessageHasATitleAndABodyWithinTheStoredLimits() {
        List<NotificationMessages.Message> all = List.of(
                NotificationMessages.proposalSubmitted("t", 1), NotificationMessages.proposalRejected("t"),
                NotificationMessages.proposalApproved("t"), NotificationMessages.proposalPublished("t"),
                NotificationMessages.proposalReviewRequested("t"), NotificationMessages.proposalCommented("t"),
                NotificationMessages.revalidationRequired("t"), NotificationMessages.baselineCreated(),
                NotificationMessages.documentReviewRequested("a.md"), NotificationMessages.documentApproved("a.md"),
                NotificationMessages.documentRejected("a.md"), NotificationMessages.documentBeeReviewDone("a.md"),
                NotificationMessages.meetingParticipantAdded("m", "r"), NotificationMessages.meetingParticipantRemoved("m"),
                NotificationMessages.meetingRecordAdded("m"));

        assertThat(all).allSatisfy(message -> {
            assertThat(message.title()).isNotBlank();
            assertThat(message.body()).isNotBlank();
            assertThat(message.title().length()).isLessThanOrEqualTo(100);
            assertThat(message.body().length()).isLessThanOrEqualTo(500);
        });
    }

    @Test
    void rejectionAndDocumentDecisionMessagesDoNotCarryTheReason() {
        // 반려 사유 원문은 알림이 아니라 상세 화면에서 본다 — 문구 빌더가 사유를 받지 않으니 들어갈 수 없다
        assertThat(NotificationMessages.proposalRejected("제안").body()).contains("제안 상세");
        assertThat(NotificationMessages.documentRejected("a.md").body()).contains("문서 상세");
    }
}
