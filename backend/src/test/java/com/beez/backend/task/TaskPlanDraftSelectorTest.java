package com.beez.backend.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.document.service.DocumentIndexWorker.ChunkEvidence;
import com.beez.backend.task.service.ProjectDesignLookup.ApprovedItem;
import com.beez.backend.task.service.TaskPlanDraftLimits;
import com.beez.backend.task.service.TaskPlanDraftSelector;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskPlanDraftSelectorTest {
    private final TaskPlanDraftLimits limits = new TaskPlanDraftLimits(12, 4000, 12000, 100, 300);

    private ChunkEvidence chunk(long id, String text, double score) {
        return new ChunkEvidence(id, 1, 2, 1, "c" + id, text, Map.of("start_line", 1, "end_line", 10), "API", score);
    }

    @Test
    void keepsLiteralAndSemanticMatchesDropsWeakHitsHeadingsAndDuplicateBodies() {
        var selected = TaskPlanDraftSelector.excerpts("로그인", List.of(
                chunk(1, "### 계정", .9), chunk(2, "게스트 로그인", .85),
                chunk(3, "게스트 로그인", .8), chunk(4, "인증 토큰을 발급한다", .83),
                chunk(5, "AI 탐색 깊이는 3이다", .3)), limits);
        assertThat(selected).extracting(ChunkEvidence::chunkId).containsExactly(2L, 4L);
        assertThat(selected.getFirst().similarity()).isEqualTo(.85);
    }

    @Test
    void compactsTableWithExactHeaderAndRowsAndKeepsSourceMetadata() {
        String source = "| 메서드 | 경로 | 설명 |\n| --- | --- | --- |\n| POST | /auth/guest | 게스트 로그인 |\n| GET | /ai/health | AI 상태 |\n";
        var original = chunk(1, source, .9);
        var selected = TaskPlanDraftSelector.excerpts("로그인", List.of(original), limits).getFirst();
        assertThat(selected.text()).contains("| 메서드 | 경로 | 설명 |", "| POST | /auth/guest | 게스트 로그인 |")
                .doesNotContain("/ai/health");
        assertThat(source).contains("| POST | /auth/guest | 게스트 로그인 |");
        assertThat(selected.locator()).isEqualTo(original.locator());
        assertThat(selected.documentVersionId()).isEqualTo(original.documentVersionId());
    }

    @Test
    void semanticOnlyTablesRemainWholeAndNoMatchDesignIsNotSent() {
        String source = "| 기능 | 내용 |\n| --- | --- |\n| 인증 | 토큰 발급 |\n";
        var selected = TaskPlanDraftSelector.excerpts("로그인", List.of(chunk(1, source, .9)), limits);
        assertThat(selected.getFirst().text()).isEqualTo(source);
        var unrelated = new ApprovedItem(3, 4, "REQ-1", "REQUIREMENT", "AI 난이도", "탐색 깊이");
        assertThat(TaskPlanDraftSelector.designItems("로그인", selected, List.of(unrelated), 100)).isEmpty();
    }

    @Test
    void ranksCompleteDesignSetByRequestOrExactSourceKeyInsteadOfAlphabeticalKey() {
        var unrelated = new ApprovedItem(1, 11, "CON-001", "CONSTRAINT", "게임 규칙", "공격 속도");
        var login = new ApprovedItem(2, 12, "REQ-999", "REQUIREMENT", "로그인", "게스트 인증");
        var reference = new ApprovedItem(3, 13, "API-4", "API_CONTRACT", "토큰", "발급");
        var evidence = List.of(chunk(1, "로그인 계약은 API-4를 따른다", .9));
        assertThat(TaskPlanDraftSelector.designItems("로그인", evidence, List.of(unrelated, login, reference), 2))
                .containsExactly(reference, login);
        assertThat(TaskPlanDraftSelector.designItems("로그인", evidence, List.of(unrelated, login), 0)).isEmpty();
    }

    @Test
    void aKeyMatchesOnlyWhenNotGluedToOtherKeyCharacters() {
        assertThat(TaskPlanDraftSelector.mentionsKey("API-4를 따른다", "API-4")).isTrue();
        assertThat(TaskPlanDraftSelector.mentionsKey("(REQ-1)", "REQ-1")).isTrue();
        assertThat(TaskPlanDraftSelector.mentionsKey("REQ-12 참고", "REQ-1")).isFalse();
        assertThat(TaskPlanDraftSelector.mentionsKey("XREQ-1", "REQ-1")).isFalse();
        // 한 번은 붙어 있고 한 번은 떨어져 있으면 떨어진 쪽으로 맞는다.
        assertThat(TaskPlanDraftSelector.mentionsKey("REQ-12 와 REQ-1", "REQ-1")).isTrue();
        assertThat(TaskPlanDraftSelector.mentionsKey("", "REQ-1")).isFalse();
        assertThat(TaskPlanDraftSelector.mentionsKey("REQ-1", "")).isFalse();
    }

    @Test
    void manyApprovedItemsAreRankedQuicklyAndDeterministically() {
        var items = new java.util.ArrayList<ApprovedItem>();
        for (int i = 0; i < 20_000; i++) items.add(new ApprovedItem(i, i + 100_000L, "REQ-" + i, "REQUIREMENT", "항목 " + i, i % 7 == 0 || i == 5 ? "로그인 처리" : "기타"));
        var evidence = List.of(chunk(1, "로그인 본문 REQ-5 참고", .9));
        long start = System.nanoTime();
        var picked = TaskPlanDraftSelector.designItems("로그인", evidence, items, 50);
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertThat(picked).hasSize(50);
        // 단어도 맞고 원문이 키(REQ-5)도 직접 말한 항목이 단어만 맞은 항목보다 앞선다(점수 2 대 1).
        assertThat(picked.getFirst().key()).isEqualTo("REQ-5");
        assertThat(picked).isEqualTo(TaskPlanDraftSelector.designItems("로그인", evidence, items, 50));
        // 점수를 정렬 비교기 안에서 다시 계산하면 이 크기에서 눈에 띄게 느려진다. 넉넉한 상한만 둔다.
        assertThat(millis).isLessThan(2_000);
    }
}
