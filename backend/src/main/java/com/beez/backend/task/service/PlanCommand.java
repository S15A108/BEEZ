package com.beez.backend.task.service;

import com.beez.backend.common.api.Ids;
import com.beez.backend.task.dto.DesignRefInput;
import com.beez.backend.task.dto.TaskPlanInput;
import com.beez.backend.task.entity.PlanRelation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 서비스 안에서 쓰는 계획 입력. API DTO(TaskPlanInput)의 문자열 ID 를 Long 으로 바꾼 것.
 * 문자열 ID 가 숫자가 아니면 {@link #from} 에서 400 validation_failed.
 */
public record PlanCommand(
        String title,
        String goal,
        List<String> includedScope,
        List<String> excludedScope,
        String approach,
        List<String> completionCriteria,
        List<Long> dependencyTaskIds,
        List<Ref> designRefs,
        List<Map<String, Object>> unlinked,
        boolean noContractChange,
        Long contextPacketId
) {

    public record Ref(Long designItemId, Long designItemVersionId, PlanRelation relation,
                      Map<String, Object> proposedContent) {
    }

    public static PlanCommand from(TaskPlanInput in) {
        List<Ref> refs = new ArrayList<>();
        for (DesignRefInput r : in.designRefs()) {
            refs.add(new Ref(Ids.parse(r.designItemId(), "design_item_id"),
                    Ids.parse(r.designItemVersionId(), "design_item_version_id"), r.relation(), r.proposedContent()));
        }
        return new PlanCommand(in.title(), in.goal(), in.includedScope(), in.excludedScope(), in.approach(),
                in.completionCriteria(), Ids.parseAll(in.dependencyTaskIds(), "dependency_task_ids"), refs,
                in.unlinked(), in.noContractChange(), Ids.parse(in.contextPacketId(), "context_packet_id"));
    }
}
