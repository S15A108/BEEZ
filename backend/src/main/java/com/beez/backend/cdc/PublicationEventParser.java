package com.beez.backend.cdc;

import java.time.Instant;
import java.util.Optional;
import java.util.HashSet;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Only Debezium outbox insert/snapshot records are business events. */
@Component
public class PublicationEventParser {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public Optional<PublicationEvent> parse(String record) {
        if (record == null) return Optional.empty(); // Kafka tombstone
        try {
            JsonNode root = JSON.readTree(record);
            JsonNode envelope = root.has("op") ? root : root.path("payload");
            String op = requiredText(envelope, "op");
            if ("d".equals(op)) return Optional.empty(); // outbox cleanup is not a publication
            if (!"c".equals(op) && !"r".equals(op)) throw new CdcEventException("Unsupported CDC operation");
            JsonNode row = envelope.path("after");
            if (!row.isObject()) throw new CdcEventException("Missing outbox row");
            JsonNode value = row.path("payload");
            JsonNode data = value.isString() ? JSON.readTree(value.asText()) : value;
            if (!data.isObject()) throw new CdcEventException("Invalid event payload");
            UUID eventId = UUID.fromString(requiredText(data, "event_id"));
            if (!eventId.toString().equals(requiredText(row, "event_id"))) throw new CdcEventException("Outbox event id mismatch");
            if (!"DESIGN_PUBLISHED".equals(requiredText(data, "event_type"))
                    || !"DESIGN_PUBLISHED".equals(requiredText(row, "event_type"))) throw new CdcEventException("Unknown event type");
            if (data.path("event_version").asInt(-1) != 1 || row.path("event_version").asInt(-1) != 1) throw new CdcEventException("Unknown event version");
            String kind = requiredText(data, "publication_kind");
            if (!"INITIAL".equals(kind) && !"CHANGE".equals(kind)) throw new CdcEventException("Unknown publication kind");
            long projectId = positiveId(data, "project_id");
            long baselineId = positiveId(data, "baseline_id");
            if (row.path("project_id").asLong(-1) != projectId || row.path("baseline_id").asLong(-1) != baselineId)
                throw new CdcEventException("Outbox scope mismatch");
            Instant.parse(requiredText(data, "occurred_at"));
            if (!data.path("changed_items").isArray()) throw new CdcEventException("Missing changed items");
            if (!data.has("previous_baseline_id") || !data.has("change_proposal_id")) throw new CdcEventException("Missing publication references");
            if ("INITIAL".equals(kind)) {
                if (!data.path("previous_baseline_id").isNull() || !data.path("change_proposal_id").isNull())
                    throw new CdcEventException("Initial publication cannot have change references");
            } else {
                long previousBaseline = positiveId(data, "previous_baseline_id");
                positiveId(data, "change_proposal_id");
                if (previousBaseline == baselineId || data.path("changed_items").isEmpty())
                    throw new CdcEventException("Change publication requires previous baseline and changed items");
            }
            var ids = new HashSet<Long>();
            for (JsonNode item : data.path("changed_items")) {
                if (!item.isObject() || !ids.add(positiveId(item, "design_item_id")))
                    throw new CdcEventException("Invalid or duplicate changed item");
                String type = requiredText(item, "change_type");
                boolean before = item.has("previous_version_id") && !item.path("previous_version_id").isNull();
                boolean after = item.has("published_version_id") && !item.path("published_version_id").isNull();
                if (!item.has("previous_version_id") || !item.has("published_version_id"))
                    throw new CdcEventException("Missing changed item version");
                if (before) positiveId(item, "previous_version_id");
                if (after) positiveId(item, "published_version_id");
                if (!("ADDED".equals(type) && !before && after)
                        && !("UPDATED".equals(type) && before && after)
                        && !("REMOVED".equals(type) && before && !after))
                    throw new CdcEventException("Invalid changed item versions");
                if ("INITIAL".equals(kind) && !"ADDED".equals(type))
                    throw new CdcEventException("Initial publication can contain added items only");
            }
            return Optional.of(new PublicationEvent(eventId, projectId, baselineId, kind, JSON.writeValueAsString(data)));
        } catch (CdcEventException e) {
            throw e;
        } catch (Exception e) {
            throw new CdcEventException("Invalid publication CDC record");
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString() || value.asText().isBlank()) throw new CdcEventException("Missing event field: " + field);
        return value.asText();
    }

    private static long positiveId(JsonNode node, String field) {
        long value = Long.parseLong(requiredText(node, field));
        if (value <= 0) throw new CdcEventException("Invalid event identifier: " + field);
        return value;
    }
}
