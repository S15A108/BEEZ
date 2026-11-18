package com.beez.backend.changediscussion.dto;

import com.beez.backend.changediscussion.repository.ChangeCommentRepository.CommentRow;
import com.beez.backend.common.api.Ids;
import java.time.Instant;
import java.util.List;

/** 명세 00-common ChangeComment (CHG-A12·A13). ID 는 문자열. 지정한 멤버가 없으면 빈 배열 */
public record ChangeCommentResponse(
        String id,
        String proposalId,
        int revision,
        String authorAccountId,
        String kind,
        String body,
        List<String> mentionedMemberIds,
        Instant createdAt
) {
    public static ChangeCommentResponse from(CommentRow row) {
        return new ChangeCommentResponse(Ids.str(row.id()), Ids.str(row.proposalId()), row.revisionNo(),
                Ids.str(row.authorAccountId()),
                row.kind(), row.body(), Ids.strs(row.mentionedMemberIds()), row.createdAt());
    }
}
