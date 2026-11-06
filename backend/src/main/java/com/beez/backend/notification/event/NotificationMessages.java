package com.beez.backend.notification.event;

/**
 * 도메인 알림 문구 (계약 docs/design/10-notification-activity-contract.md 3절).
 * 제목·본문은 "어떤 일이 있었다"까지만 쓴다 — 의견 원문, 반려 사유, 문서 내용은 넣지 않고 상세 화면에서 권한 검사 뒤 본다.
 * 사용자가 입력한 이름(제안 제목, 파일명)은 들어가며, 길이 제한은 {@code NotificationRequester} 가 코드 포인트 기준으로 자른다.
 */
public final class NotificationMessages {

    /** 제목 글자 수 (코드 포인트). 알림 제목 한도 100자 안에서 접두 문구가 잘리지 않게 이름 부분만 줄인다 */
    private static final int NAME_MAX = 40;

    private NotificationMessages() {
    }

    public record Message(String title, String body) {
    }

    // ---- 변경 제안 ----

    public static Message proposalSubmitted(String proposalTitle, int revision) {
        return new Message("변경 제안이 올라왔습니다: " + name(proposalTitle),
                revision > 1 ? "보완된 변경 제안이 다시 제출되었습니다. 내가 맡은 작업에 영향이 있는지 확인해 주세요."
                        : "새 변경 제안이 제출되었습니다. 내가 맡은 작업에 영향이 있는지 확인해 주세요.");
    }

    public static Message proposalRejected(String proposalTitle) {
        return new Message("변경 제안이 반려되었습니다: " + name(proposalTitle),
                "팀장이 변경 제안을 반려했습니다. 반려 사유는 제안 상세에서 확인하고 보완해 다시 제출할 수 있습니다.");
    }

    public static Message proposalApproved(String proposalTitle) {
        return new Message("변경 제안이 승인되었습니다: " + name(proposalTitle),
                "팀장이 변경 제안을 승인하고 새 기준으로 발행했습니다.");
    }

    public static Message proposalPublished(String proposalTitle) {
        return new Message("변경이 발행되었습니다: " + name(proposalTitle),
                "내 작업과 관련된 변경 제안이 새 기준으로 발행되었습니다. 변경 내용을 확인해 주세요.");
    }

    public static Message proposalReviewRequested(String proposalTitle) {
        return new Message("변경 제안 검토 요청: " + name(proposalTitle),
                "변경 제안의 검토 요청을 받았습니다. 제안 상세의 의견을 확인해 주세요.");
    }

    public static Message proposalCommented(String proposalTitle) {
        return new Message("변경 제안 의견에서 지정되었습니다: " + name(proposalTitle),
                "변경 제안 의견에서 내가 지정되었습니다. 제안 상세의 의견을 확인해 주세요.");
    }

    public static Message revalidationRequired(String taskTitle) {
        return new Message("작업 재검증이 필요합니다: " + name(taskTitle),
                "변경 발행으로 작업이 재검증 대기 상태가 되었습니다. 변경 내용을 확인하고 이어서 진행해 주세요.");
    }

    public static Message taskAssigned(String taskTitle) {
        return new Message("작업 담당자로 지정되었습니다: " + name(taskTitle),
                "작업의 담당자가 나로 바뀌었습니다. 작업 상세에서 계획과 상태를 확인해 주세요.");
    }

    // ---- 기준 ----

    public static Message baselineCreated() {
        return new Message("최초 기준이 만들어졌습니다", "프로젝트의 최초 기준 설계(v1)가 발행되었습니다. 이제 작업 계획과 변경 제안을 시작할 수 있습니다.");
    }

    // ---- 문서 검토 ----

    public static Message documentReviewRequested(String fileName) {
        return new Message("문서 검토 요청: " + name(fileName), "문서 승인 요청이 도착했습니다. 문서를 검토하고 승인 또는 반려해 주세요.");
    }

    public static Message documentApproved(String fileName) {
        return new Message("문서가 승인되었습니다: " + name(fileName), "제출한 문서가 팀장에게 승인되었습니다.");
    }

    public static Message documentRejected(String fileName) {
        return new Message("문서가 반려되었습니다: " + name(fileName), "제출한 문서가 반려되었습니다. 반려 사유는 문서 상세에서 확인해 주세요.");
    }

    public static Message documentBeeReviewDone(String fileName) {
        return new Message("비비의 문서 검토가 끝났습니다: " + name(fileName), "비비가 문서를 검토했습니다. 제안을 확인하고 반영 여부를 정해 주세요.");
    }

    // ---- 회의 ----

    /** reason 은 MTG-A04 가 알림에 보낸다고 명시한 값이다. 비어 있으면 생략하고, 길면 저장 때 500자로 잘린다 */
    public static Message meetingParticipantAdded(String meetingTitle, String reason) {
        String why = reason == null || reason.isBlank() ? "" : " 이유: " + reason.strip();
        return new Message("회의에 참여자로 추가되었습니다: " + name(meetingTitle), "회의 참여자로 추가되었습니다." + why);
    }

    public static Message meetingParticipantRemoved(String meetingTitle) {
        return new Message("회의 참여에서 제외되었습니다: " + name(meetingTitle), "팀장이 회의 참여자에서 제외했습니다.");
    }

    public static Message meetingRecordAdded(String meetingTitle) {
        return new Message("회의에 새 기록이 올라왔습니다: " + name(meetingTitle), "참여 중인 회의에 새 기록이 추가되었습니다.");
    }

    /** 비비가 작업 충돌로 연 회의. 충돌 내용 원문은 넣지 않는다 — 회의 안에서 본다 */
    public static Message meetingOpened(String meetingTitle) {
        return new Message("작업 충돌로 회의가 열렸습니다: " + name(meetingTitle), "비비가 작업 충돌을 감지해 회의를 열었습니다. 회의에서 어느 쪽을 따를지 정해 주세요.");
    }

    /** MTG-A13. 회의 결과가 변경 제안으로 올라갔다. 문서와 기준은 팀장이 제안을 승인할 때 바뀌므로 "바뀌었다"고 쓰지 않는다 */
    public static Message meetingProposalCreated(String meetingTitle, String proposalTitle) {
        return new Message("회의 결과가 변경 제안으로 올라갔습니다: " + name(meetingTitle),
                "회의에서 정리한 문서 변경이 제안되었습니다. 제안: " + name(proposalTitle));
    }

    /** 이름이 길면 줄이고, 비어 있으면 일반 표현으로 바꾼다 */
    static String name(String value) {
        if (value == null || value.isBlank()) {
            return "(이름 없음)";
        }
        String text = value.strip();
        if (text.codePointCount(0, text.length()) <= NAME_MAX) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, NAME_MAX - 1)) + "…";
    }
}
