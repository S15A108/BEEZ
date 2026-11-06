package com.beez.backend.invitation;

/**
 * MEM-A04 응답. 로그인 전 화면에 필요한 최소 정보만. 만료가 없어 expires_at 은 내보내지 않는다 (Q05 6).
 *
 * @param invitedEmail   이메일을 지정한 초대면 그 주소
 * @param accountMatches 로그인 상태일 때만. 로그인 계정이 이 초대로 들어갈 수 있는 사람인지 —
 *                       화면은 true 면 버튼 없이 MEM-A05 를 부르고, false 면 다시 로그인하게 한다
 */
public record InvitationPreviewResponse(
        String projectName,
        String projectDescription,
        boolean loginRequired,
        Inviter inviter,
        String invitedEmail,
        Boolean accountMatches) {

    /** 초대한 사람. 그 계정이 탈퇴했으면 inviter 자체를 내보내지 않는다. */
    public record Inviter(String name, String email) {
    }
}
