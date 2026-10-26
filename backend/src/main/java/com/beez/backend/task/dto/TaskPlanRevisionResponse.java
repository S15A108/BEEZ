package com.beez.backend.task.dto;

import com.beez.backend.common.api.Ids;
import com.beez.backend.task.entity.TaskPlanDesignRef;
import com.beez.backend.task.entity.TaskPlanRevision;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 명세 00-common TaskPlanRevision. plan 안에 당시 참조 버전을 그대로 담는다 (최신으로 치환하지 않음). ID 는 문자열. */
public record TaskPlanRevisionResponse(
        String id,
        String taskId,
        Integer revisionNo,
        TaskPlanInput plan,
        Instant createdAt,
        String createdBy,
        String agentTokenId
) {
    public static TaskPlanRevisionResponse from(TaskPlanRevision r, List<TaskPlanDesignRef> refs) {
        List<DesignRefInput> refInputs = new ArrayList<>();
        for (TaskPlanDesignRef ref : refs) {
            refInputs.add(new DesignRefInput(Ids.str(ref.getDesignItemId()), Ids.str(ref.getDesignItemVersionId()),
                    ref.getRelation(), ref.getProposedContent()));
        }
        TaskPlanInput plan = new TaskPlanInput(
                r.getTitle(), r.getGoal(), r.getIncludedScope(), r.getExcludedScope(), r.getApproach(),
                r.getCompletionCriteria(), Ids.strs(r.getDependencyTaskIds()), refInputs, r.getUnlinked(),
                r.isNoContractChange(), Ids.str(r.getContextPacketId()));
        return new TaskPlanRevisionResponse(Ids.str(r.getId()), Ids.str(r.getTaskId()), r.getRevisionNo(), plan,
                r.getCreatedAt(), Ids.str(r.getCreatedByAccountId()), Ids.str(r.getCreatedByTokenId()));
    }
}
