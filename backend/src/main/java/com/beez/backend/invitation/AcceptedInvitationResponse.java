package com.beez.backend.invitation;

import com.beez.backend.project.MemberResponse;
import com.beez.backend.project.ProjectResponse;

/** MEM-A05 응답. 참여한 프로젝트와 만든(되살린) MEMBER 멤버십. */
public record AcceptedInvitationResponse(ProjectResponse project, MemberResponse member) {
}
