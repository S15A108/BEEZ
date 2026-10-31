package com.beez.backend.validation.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

class ValidationEvidenceBuilderTest {
    private final JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
    private final ValidationEvidenceBuilder builder = new ValidationEvidenceBuilder(jdbc, JsonMapper.builder().build());

    private void rows(List<Map<String, Object>> rows) {
        when(jdbc.sql(anyString()).param(anyString(), any()).param(anyString(), any())
                .param(anyString(), any()).param(anyString(), any()).query().listOfRows()).thenReturn(rows);
    }
    private static String digest(String source) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
    }
    private static Map<String, Object> item() {
        return Map.of("id", "101", "design_item_id", "10", "source", Map.of("document_id", "20",
                "document_version_id", "201", "version_no", 1, "location", "API 명세"));
    }
    private static Map<String, Object> row(String source, String locator, String hash) {
        var row = new LinkedHashMap<String, Object>();
        row.put("item_source_id", 201L); row.put("locator", locator); row.put("content_text", source);
        row.put("text_hash", hash); row.put("baseline_source_id", 201L); row.put("document_id", 20L);
        row.put("document_version_id", 201L); row.put("version_no", 1); row.put("document_status", "APPROVED");
        return row;
    }

    @Test void recoversExactLfBoundedExcerptAndFullDocumentDigest() throws Exception {
        String source = "# API\nmemberId\n이름\n";
        rows(List.of(row(source, "{\"location\":\"API 명세\",\"start_line\":2,\"end_line\":3}", "sha256:" + digest(source))));
        var result = builder.build(1, 2, List.of(item()));
        assertTrue(result.unavailableVersionIds().isEmpty());
        var evidence = result.evidence().getFirst();
        assertEquals("memberId\n이름\n", evidence.get("text"));
        assertEquals(digest(source), evidence.get("text_hash"));
        assertEquals(Map.of("type", "text_lines", "start_line", 2, "end_line", 3), evidence.get("locator"));
        assertEquals(item().get("source"), evidence.get("source"));
        assertEquals("101", evidence.get("design_item_version_id"));
    }

    @Test void missingExplicitLineRangeIsUnavailableRatherThanInvented() throws Exception {
        String source = "api\n";
        rows(List.of(row(source, "{\"location\":\"API 명세\"}", "sha256:" + digest(source))));
        var result = builder.build(1, 2, List.of(item()));
        assertTrue(result.evidence().isEmpty());
        assertEquals(List.of("101"), result.unavailableVersionIds());
    }

    @Test void outOfBoundsLocatorIsUnavailableInsteadOfRetriedAsInfrastructureFailure() throws Exception {
        String source = "api\n";
        var locator = "{\"location\":\"API 명세\",\"start_line\":1,\"end_line\":5000}";
        rows(List.of(row(source, locator, "sha256:" + digest(source))));
        var result = builder.build(1, 2, List.of(item()));
        assertTrue(result.evidence().isEmpty());
        assertEquals(List.of("101"), result.unavailableVersionIds());
    }
    @Test void omittedSourceDoesNotHideExistingSourceDocument() {
        var row = row("text\n", null, null);
        rows(List.of(row));
        var result = builder.build(1, 2, List.of(Map.of("id", "101", "design_item_id", "10")));
        assertEquals(List.of("101"), result.unavailableVersionIds());
    }

    @Test void documentOutsideBaselineIsNeverReturned() {
        var row = row("text\n", null, null); row.put("baseline_source_id", null);
        rows(List.of(row));
        var result = builder.build(1, 2, List.of(item()));
        assertTrue(result.evidence().isEmpty());
        assertEquals(List.of("101"), result.unavailableVersionIds());
    }

    @Test void invalidFullDocumentHashOrSourceIdentityIsRejected() {
        String locator = "{\"location\":\"API 명세\",\"start_line\":1,\"end_line\":1}";
        var row = row("text\n", locator, "sha256:not-a-match");
        rows(List.of(row));
        assertThrows(IllegalStateException.class, () -> builder.build(1, 2, List.of(item())));
        row.put("document_id", 999L);
        assertThrows(IllegalStateException.class, () -> builder.build(1, 2, List.of(item())));
    }

    @Test void acceptsOnlyUnambiguousExplicitDisplayLineRanges() {
        assertArrayEquals(new int[] { 2, 3 }, ValidationEvidenceBuilder.displayLineRange("2~3행"));
        assertArrayEquals(new int[] { 2, 2 }, ValidationEvidenceBuilder.displayLineRange("2행"));
        assertArrayEquals(new int[] { 2, 3 }, ValidationEvidenceBuilder.displayLineRange(" 2 ~ 3행 "));
        for (String ambiguous : List.of("1절", "서문 2~3행", "3~2행", "0행", "2-", "1~99999999999999999999행")) {
            assertNull(ValidationEvidenceBuilder.displayLineRange(ambiguous));
        }
    }
    @Test void lineRangesRetainFinalNewlinesAndRejectOutOfBounds() {
        assertEquals("a\n\n", ValidationEvidenceBuilder.excerpt("a\n\nb", 1, 2));
        assertEquals("b", ValidationEvidenceBuilder.excerpt("a\n\nb", 3, 3));
        assertThrows(IllegalArgumentException.class, () -> ValidationEvidenceBuilder.excerpt("a\n", 2, 2));
        assertThrows(IllegalArgumentException.class, () -> ValidationEvidenceBuilder.excerpt("a\n", 2, 1));
    }
}
