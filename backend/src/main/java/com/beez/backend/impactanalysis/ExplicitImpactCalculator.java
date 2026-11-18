package com.beez.backend.impactanalysis;

import java.util.*;
import org.springframework.stereotype.Component;

/** Only submitted changes and confirmed incoming edges produce rule impacts. */
@Component
public class ExplicitImpactCalculator {
    public List<Map<String, String>> calculate(ImpactContextQuery.Snapshot snapshot) {
        Set<Long> changed = new LinkedHashSet<>();
        snapshot.changes().stream().map(ImpactContextQuery.Change::itemId).filter(Objects::nonNull).forEach(changed::add);
        Map<Long, Set<Long>> targets = new LinkedHashMap<>();
        changed.forEach(id -> targets.computeIfAbsent(id, ignored -> new LinkedHashSet<>()).add(id));
        snapshot.dependencies().stream().filter(edge -> changed.contains(edge.toItemId())).forEach(edge ->
                targets.computeIfAbsent(edge.fromItemId(), ignored -> new LinkedHashSet<>()).add(edge.toItemId()));
        Set<Map<String, String>> result = new LinkedHashSet<>();
        for (var ref : snapshot.taskReferences()) {
            if (Set.of("COMPLETED", "CANCELLED").contains(ref.status())) continue;
            for (long target : targets.getOrDefault(ref.designItemId(), Set.of())) {
                Map<String, String> value = new LinkedHashMap<>();
                value.put("task_id", Long.toString(ref.taskId()));
                value.put("design_item_id", Long.toString(target));
                if (ref.assigneeMemberId() != null) value.put("member_id", ref.assigneeMemberId().toString());
                if (ref.validationId() != null) value.put("validation_id", ref.validationId().toString());
                result.add(Map.copyOf(value));
            }
        }
        return List.copyOf(result);
    }
}
