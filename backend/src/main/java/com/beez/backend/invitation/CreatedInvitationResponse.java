package com.beez.backend.invitation;

import com.beez.backend.common.api.Ids;
import java.util.List;

/**
 * MEM-A01 응답. 두 모양 중 하나다 (빈 칸은 직렬화에서 빠진다).
 * <ul>
 *   <li>링크 초대(email·본문 없음): invitation + invite_url. invite_url 에만 평문 토큰이 들어가고, 다시 조회할 방법은 없다.</li>
 *   <li>이메일 초대(emails): results 만. 토큰은 메일에만 담는다.</li>
 * </ul>
 */
public record CreatedInvitationResponse(InvitationResponse invitation, String inviteUrl, List<Result> results) {

    static CreatedInvitationResponse link(InvitationResponse invitation, String inviteUrl) {
        return new CreatedInvitationResponse(invitation, inviteUrl, null);
    }

    static CreatedInvitationResponse emails(List<Result> results) {
        return new CreatedInvitationResponse(null, null, results);
    }

    /**
     * 이메일별 결과. 가입한 이메일이든 아니든 새 초대는 INVITED 다 — 응답으로 가입 여부를 알 수 없게 한다 (Q05 3).
     * invitation_id 는 INVITED·ALREADY_INVITED 에만 있다.
     */
    public record Result(String email, Status status, String invitationId) {

        static Result of(String email, Status status, Long invitationId) {
            return new Result(email, status, invitationId == null ? null : Ids.str(invitationId));
        }
    }

    public enum Status {
        INVITED, ALREADY_MEMBER, ALREADY_INVITED
    }
}
