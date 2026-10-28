package com.beez.backend.designitem;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;

/** IDs are decimal strings in responses; request IDs also accept the existing JSON string form. */
public final class DesignItemDtos {
    public record Create(@NotBlank String kind, @NotNull @Positive Integer schema_version,
                         @NotNull Map<String, Object> content, @Positive Long source_document_version_id,
                         @Size(max = 2000) String source_location) { }
    public record Update(@NotNull @Positive Long project_id, @NotNull @Positive Long expected_version_id,
                         @NotNull Map<String, Object> content, @NotBlank String expected_content_hash) { }
    public record Publish(@NotNull @Positive Long project_id,
                          @NotEmpty List<@NotNull @Positive Long> document_version_ids,
                          @NotEmpty List<@NotNull @Positive Long> design_item_version_ids,
                          @NotBlank String draft_snapshot_hash) { }
    public record Source(String document_id, String document_version_id, int version_no, String location) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(String id, String design_item_id, String key, String kind, int version_no,
                       String status, int schema_version, Map<String, Object> content,
                       Source source, String content_hash) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Page(List<Item> items, boolean has_more, String next_cursor, String draft_snapshot_hash) { }
    public record DocumentRef(String document_id, String document_version_id) { }
    public record ItemRef(String design_item_id, String design_item_version_id) { }
    public record Baseline(String id, String project_id, int version_no, List<DocumentRef> documents,
                           List<ItemRef> items, String approved_by, String created_at) { }
    private DesignItemDtos() { }
}
