package com.beez.backend.meeting.service;

import static com.beez.backend.designitem.support.DesignJson.JSON;

import com.beez.backend.common.api.Ids;
import com.beez.backend.designitem.support.DesignJson;
import com.beez.backend.meeting.dto.MeetingConflictGuidance;
import com.beez.backend.meeting.dto.MeetingConflictGuidance.Conflict;
import com.beez.backend.meeting.dto.MeetingConflictGuidance.DesignItem;
import com.beez.backend.meeting.dto.MeetingConflictGuidance.Difference;
import com.beez.backend.meeting.dto.MeetingConflictGuidance.Task;
import com.beez.backend.meeting.repository.MeetingRepository.ConflictDesign;
import com.beez.backend.meeting.repository.MeetingRepository.ConflictFinding;
import com.beez.backend.meeting.repository.MeetingRepository.ConflictPlan;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

final class ConflictGuidanceBuilder {
    private static final int MAX_DIFFERENCES = 12;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_DISPLAY_CHARS = 400;
    private static final Object ABSENT = new Object();

    private ConflictGuidanceBuilder() {}

    static MeetingConflictGuidance build(long validationId, List<ConflictFinding> findings) {
        if (findings.isEmpty()) return null;
        ConflictFinding first = findings.getFirst();
        List<Conflict> conflicts = findings.stream().map(finding -> new Conflict(
                finding.message(), finding.nextAction(), designItem(finding.design()),
                differences(finding.firstProposedContent(), finding.secondProposedContent()))).toList();
        return new MeetingConflictGuidance(Ids.str(validationId), List.of(task(first.first()), task(first.second())), conflicts);
    }

    private static Task task(ConflictPlan plan) {
        return new Task(Ids.str(plan.taskId()), plan.title(), Ids.str(plan.revisionId()), plan.revisionNo());
    }

    private static DesignItem designItem(ConflictDesign design) {
        if (design == null) return null;
        return new DesignItem(Ids.str(design.itemId()), Ids.str(design.versionId()), design.key(), design.title(),
                Ids.str(design.documentId()), Ids.str(design.documentVersionId()),
                design.documentId() == null ? null : sourceLocation(design.locator()));
    }

    private static String sourceLocation(String locator) {
        if (locator == null) return null;
        var node = JSON.readTree(locator);
        if (node.path("location").isTextual() && !node.path("location").asText().isBlank()) {
            return node.path("location").asText();
        }
        if (node.hasNonNull("start_line") && node.hasNonNull("end_line")) {
            return "L" + node.path("start_line").asInt() + "-L" + node.path("end_line").asInt();
        }
        return null;
    }

    /** No field difference can be inferred unless both plans contain a proposed body for this item. */
    private static List<Difference> differences(String first, String second) {
        if (first == null || second == null) return List.of();
        var result = new ArrayList<Difference>();
        compare(DesignJson.object(first), DesignJson.object(second), "", 0, result);
        return List.copyOf(result);
    }

    private static void compare(Object first, Object second, String path, int depth, List<Difference> differences) {
        if (Objects.equals(first, second) || differences.size() == MAX_DIFFERENCES) return;
        if (depth < MAX_DEPTH && first instanceof Map<?, ?> left && second instanceof Map<?, ?> right) {
            var keys = new TreeSet<String>();
            left.keySet().forEach(key -> keys.add((String) key));
            right.keySet().forEach(key -> keys.add((String) key));
            for (String key : keys) {
                if (differences.size() == MAX_DIFFERENCES) break;
                compare(value(left, key), value(right, key), childPath(path, key), depth + 1, differences);
            }
            return;
        }
        // Show arrays as a whole: a positional comparison could mispair reordered fields.
        boolean plainText = first instanceof String && (second instanceof String || second == ABSENT)
                || second instanceof String && first == ABSENT;
        differences.add(new Difference(clip(path), display(first, plainText), display(second, plainText)));
    }

    private static Object value(Map<?, ?> object, String key) {
        return object.containsKey(key) ? object.get(key) : ABSENT;
    }

    private static String childPath(String parent, String key) {
        if (key.matches("[\\p{L}_][\\p{L}\\p{N}_-]*")) return parent.isEmpty() ? key : parent + "." + key;
        return parent + "[" + JSON.writeValueAsString(key) + "]";
    }

    private static String display(Object value, boolean plainText) {
        if (value == ABSENT) return null;
        return clip(plainText ? (String) value : JSON.writeValueAsString(value));
    }

    private static String clip(String value) {
        if (value.length() <= MAX_DISPLAY_CHARS) return value;
        int end = Character.isHighSurrogate(value.charAt(MAX_DISPLAY_CHARS - 1)) ? MAX_DISPLAY_CHARS - 1 : MAX_DISPLAY_CHARS;
        return value.substring(0, end) + "…";
    }
}
