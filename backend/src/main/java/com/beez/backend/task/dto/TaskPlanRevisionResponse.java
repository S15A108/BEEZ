package com.beez.backend.task.dto;

import com.beez.backend.task.entity.TaskPlanDesignRef;
import com.beez.backend.task.entity.TaskPlanRevision;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 명세 00-common TaskPlanRevision. plan 안에 당시 참조 버전을 그대로 담는다 (최신으로 치환하지 않음). */
public record TaskPlanRevisionResponse(
        Long id,
        Long taskId,
        Integer revisionNo,
        TaskPlanInput plan,
        Instant createdAt,
        Long createdBy,
        Long agentTokenId
) {
    public static TaskPlanRevisionResponse from(TaskPlanRevision r, List<TaskPlanDesignRef> refs) {
        List<DesignRefInput> refInputs = new ArrayList<>();
        for (TaskPlanDesignRef ref : refs) {
            refInputs.add(new DesignRefInput(ref.getDesignItemId(), ref.getDesignItemVersionId(),
                    ref.getRelation(), ref.getProposedContent()));
        }
        TaskPlanInput plan = new TaskPlanInput(
                r.getTitle(), r.getGoal(), r.getIncludedScope(), r.getExcludedScope(), r.getApproach(),
                r.getCompletionCriteria(), r.getDependencyTaskIds(), refInputs, r.getUnlinked(),
                r.isNoContractChange(), r.getContextPacketId());
        return new TaskPlanRevisionResponse(r.getId(), r.getTaskId(), r.getRevisionNo(), plan,
                r.getCreatedAt(), r.getCreatedByAccountId(), r.getCreatedByTokenId());
    }
}
