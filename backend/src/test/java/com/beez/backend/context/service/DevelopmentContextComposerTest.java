package com.beez.backend.context.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DevelopmentContextComposerTest {

    /**
     * 깨지면: 10만 자 상한에서 이모지 같은 서로게이트 쌍의 앞 절반만 남아, 짝 없는 서로게이트가 든 본문을 AI 가 거부하고
     * MCP-01 이 503 으로 끝난다 (AI CandidateText 상한은 코드 포인트, -132 6절).
     */
    @Test
    void candidateText_cutsAtCodePointLimit_withoutSplittingSurrogatePairs() {
        // "K t\n" 4자 뒤에 이모지를 채워 상한(100,000 코드 포인트)이 이모지 한가운데(UTF-16 기준)에 걸리게 한다
        String content = "😀".repeat(DevelopmentContextComposer.MAX_CANDIDATE_TEXT);
        String text = DevelopmentContextComposer.candidateText(Map.of("key", "K", "title", "t", "content", content));

        assertThat(text.codePointCount(0, text.length())).isEqualTo(DevelopmentContextComposer.MAX_CANDIDATE_TEXT);
        assertThat(Character.isHighSurrogate(text.charAt(text.length() - 1))).isFalse();
    }
}
