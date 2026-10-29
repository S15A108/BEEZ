package com.beez.backend.designitem;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.designitem.dto.CreateDesignItemRequest;
import com.beez.backend.designitem.dto.DesignItemResponse;
import com.beez.backend.designitem.dto.DesignItemSourceResponse;
import com.beez.backend.designitem.dto.PublishInitialBaselineRequest;
import com.beez.backend.designitem.dto.UpdateDesignItemRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

class DesignItemDtoContractTest {
    private static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

    @Test
    void camelCaseRequestRecordsKeepSnakeCaseJsonAndDecimalStringIds() {
        var update = JSON.readValue("""
                {"project_id":"101","expected_version_id":"311","expected_content_hash":"hash",
                 "content":{"description":"조회"}}
                """, UpdateDesignItemRequest.class);
        assertThat(update.projectId()).isEqualTo("101");
        assertThat(update.expectedVersionId()).isEqualTo("311");
        var request = new PublishInitialBaselineRequest("101", List.of("211"), List.of("311"), "snapshot");
        assertThat(JSON.readTree(JSON.writeValueAsString(request))).isEqualTo(JSON.readTree("""
                {"project_id":"101","document_version_ids":["211"],"design_item_version_ids":["311"],
                 "draft_snapshot_hash":"snapshot"}
                """));
        var create = JSON.readValue("""
                {"kind":"REQUIREMENT","schema_version":1,"content":{"description":"조회"},
                 "source_document_version_id":"211","source_location":"1절"}
                """, CreateDesignItemRequest.class);
        assertThat(create.sourceDocumentVersionId()).isEqualTo("211");
        assertThat(create.schemaVersion()).isEqualTo(1);
    }

    @Test
    void responseJsonUsesSnakeCaseAndPreservesOptionalFields() {
        String responseJson = """
                {"id":"311","design_item_id":"301","key":"REQ-001","kind":"REQUIREMENT",
                 "version_no":1,"status":"DRAFT","schema_version":1,"content":{"description":"조회"},
                 "source":{"document_id":"201","document_version_id":"211","version_no":1,"location":"1절"},
                 "content_hash":"hash"}
                """;
        var response = JSON.readValue(responseJson, DesignItemResponse.class);
        assertThat(response.designItemId()).isEqualTo("301");
        assertThat(response.source()).isEqualTo(new DesignItemSourceResponse("201", "211", 1, "1절"));
        assertThat(JSON.readTree(JSON.writeValueAsString(response))).isEqualTo(JSON.readTree(responseJson));
        var withoutSource = new DesignItemResponse("311", "301", "REQ-001", "REQUIREMENT", 1,
                "DRAFT", 1, Map.of("description", "조회"), null, "hash");
        assertThat(JSON.readTree(JSON.writeValueAsString(withoutSource)).has("source")).isFalse();
    }
}
