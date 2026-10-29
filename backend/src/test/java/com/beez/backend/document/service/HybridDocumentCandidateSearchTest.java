package com.beez.backend.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.beez.backend.common.auth.CallerContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HybridDocumentCandidateSearchTest {
    private final DocumentApplicationService documents = mock(DocumentApplicationService.class);
    private final HybridDocumentCandidateSearch search = new HybridDocumentCandidateSearch(documents);
    private final CallerContext caller = CallerContext.human(1L);

    private Map<String, Object> page(boolean more, Map<String, Object>... hits) {
        return Map.of("items", List.of(hits), "has_more", more);
    }

    @Test void combinesHitsWithoutTreatingDocumentChunksAsDesignItems() {
        when(documents.baseline(caller, 1L, null)).thenReturn(Map.of("id", "9"));
        var source = Map.<String, Object>of("document_id", "5", "document_version_id", "7", "location", "1~3행");
        when(documents.search(caller, 1L, "회원", "KEYWORD", null, null, null, 10))
                .thenReturn(page(true, Map.of("type", "DESIGN_ITEM", "id", "5"),
                        Map.of("type", "DOCUMENT", "id", "5", "source", source)));
        when(documents.search(caller, 1L, "회원", "SEMANTIC", null, null, null, 10))
                .thenReturn(page(false, Map.of("type", "DOCUMENT_CHUNK", "id", "5", "source", source)));

        var result = search.search(caller, 1L, "회원", null, null, 10);

        assertThat(result.baselineId()).isEqualTo("9");
        assertThat(result.keywordHasMore()).isTrue();
        assertThat(result.semanticHasMore()).isFalse();
        assertThat(result.candidates()).hasSize(3);
        assertThat(result.candidates().get(2).hit()).containsEntry("type", "DOCUMENT_CHUNK").containsEntry("source", source);
        assertThat(result.candidates().get(2).searchModes()).containsExactly("SEMANTIC");
    }

    @Test void identicalTypeAndIdIsDeduplicatedButSearchOriginsAreRetained() {
        when(documents.baseline(caller, 1L, null)).thenReturn(Map.of("id", "9"));
        var item = Map.<String, Object>of("type", "DOCUMENT", "id", "8");
        when(documents.search(eq(caller), eq(1L), eq("x"), anyString(), isNull(), isNull(), isNull(), eq(3)))
                .thenReturn(page(false, item));
        var result = search.search(caller, 1L, "x", null, null, 3);
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().getFirst().searchModes()).containsExactlyInAnyOrder("KEYWORD", "SEMANTIC");
    }

    @Test void changingBaselineRejectsMixedSnapshot() {
        when(documents.baseline(caller, 1L, null)).thenReturn(Map.of("id", "9"), Map.of("id", "10"));
        when(documents.search(eq(caller), eq(1L), eq("x"), anyString(), isNull(), isNull(), isNull(), eq(3)))
                .thenReturn(page(false));
        assertThatThrownBy(() -> search.search(caller, 1L, "x", null, null, 3))
                .satisfies(error -> assertThat(((com.beez.backend.common.api.ApiException) error).getCode()).isEqualTo("baseline_changed"));
    }

    @Test void semanticFailureIsNotConvertedToAnEmptySuccess() {
        when(documents.baseline(caller, 1L, null)).thenReturn(Map.of("id", "9"));
        when(documents.search(caller, 1L, "x", "KEYWORD", null, null, null, 3)).thenReturn(page(false));
        var failure = DocumentValues.conflict("index_not_ready");
        when(documents.search(caller, 1L, "x", "SEMANTIC", null, null, null, 3)).thenThrow(failure);
        assertThatThrownBy(() -> search.search(caller, 1L, "x", null, null, 3)).isSameAs(failure);
    }
}
