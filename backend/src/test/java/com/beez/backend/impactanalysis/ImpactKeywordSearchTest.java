package com.beez.backend.impactanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImpactKeywordSearchTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private ImpactContextQuery.Change change(String operation, String content) {
        return new ImpactContextQuery.Change(0, operation, null, null, "API_CONTRACT", null, content, NOW);
    }

    @Test
    void extractsFieldNamesAndValuesForNewDesignsWithoutInventingGraphRelations() {
        var terms = ImpactKeywordSearch.terms("회원 조회", List.of(change("CREATE",
                "{\"response\":{\"memberId\":\"long\"},\"path\":\"/members\"}")), List.of());
        assertThat(terms.status()).isEqualTo(ImpactKeywordSearch.Status.SEARCHED);
        assertThat(terms.values()).contains("memberid", "members", "회원", "조회");
        assertThat(terms.values()).doesNotContain("response", "path");
    }

    @Test
    void noUsableWordsDoesNotBecomeAnUnrestrictedDatabaseQuery() {
        var terms = ImpactKeywordSearch.terms("123", List.of(change("CREATE", "{\"x\":2}")), List.of());
        assertThat(terms.status()).isEqualTo(ImpactKeywordSearch.Status.NO_USABLE_TERMS);
        assertThat(terms.values()).isEmpty();
        assertThatThrownBy(() -> ImpactKeywordSearch.predicate("v.title", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fieldValuesUseBoundSubstringsNotSqlOrLikeWildcards() {
        var terms = ImpactKeywordSearch.terms("", List.of(change("CREATE",
                "{\"user_name\":\"john%_'; DROP TABLE task\"}")), List.of());
        assertThat(terms.values()).contains("user_name", "john", "drop", "table", "task");
        String predicate = ImpactKeywordSearch.predicate("v.title", terms.values().size());
        assertThat(predicate).contains("strpos(lower(v.title), :term0)");
        assertThat(predicate).doesNotContain("DROP", "john", "like");
    }

    @Test
    void limitsAreVisibleAndOversizedInputsAreNotSearched() {
        String many = "{\"title\":\"alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi\"}";
        var terms = ImpactKeywordSearch.terms("", List.of(change("CREATE", many)), List.of());
        assertThat(terms.values()).hasSize(ImpactKeywordSearch.MAX_TERMS);
        assertThat(terms.termsTruncated()).isTrue();
        var tooLarge = ImpactKeywordSearch.terms("a".repeat(262145), List.of(), List.of());
        assertThat(tooLarge.status()).isEqualTo(ImpactKeywordSearch.Status.INPUT_TOO_LARGE);
        assertThat(tooLarge.values()).isEmpty();
    }
}
