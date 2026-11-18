package com.beez.backend.context.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DevelopmentContextComposerTest {

    /**
     * 깨지면: 큰 데이터 계약 하나의 본문 전문이 후보 텍스트로 들어가 AI 임베딩 한도(텍스트당 8,192토큰)를 넘고,
     * AI 가 요청 전체를 422 로 거부해 프로젝트의 MCP-01 이 계속 503 이 된다 (ai tokenization.py MAX_INPUT_TOKENS).
     */
    @Test
    void candidateText_usesIdentifiersAndDescription_notTheWholeContent() {
        Map<String, Object> content = Map.of("entity", "Member", "description", "회원",
                "fields", Collections.nCopies(5_000, Map.of("name", "column_with_a_long_name")));

        String text = DevelopmentContextComposer.candidateText(Map.of("key", "DATA-01", "title", "회원 테이블"), content, "회원");

        assertThat(text).isEqualTo("DATA-01 회원 테이블\nMember\n회원");
    }

    /**
     * 깨지면: 상한에서 이모지 같은 서로게이트 쌍의 앞 절반만 남아 짝 없는 서로게이트가 든 본문을 AI 가 거부하거나,
     * 상한을 넘는 설명이 그대로 가 임베딩 한도에 걸린다 (AI CandidateText 상한은 코드 포인트, -132 6절).
     */
    @Test
    void candidateText_cutsAtCodePointLimit_withoutSplittingSurrogatePairs() {
        String description = "😀".repeat(DevelopmentContextComposer.MAX_CANDIDATE_TEXT);

        String text = DevelopmentContextComposer.candidateText(Map.of("key", "K", "title", "t"), Map.of(), description);

        assertThat(text.codePointCount(0, text.length())).isEqualTo(DevelopmentContextComposer.MAX_CANDIDATE_TEXT);
        assertThat(Character.isHighSurrogate(text.charAt(text.length() - 1))).isFalse();
    }

    /**
     * 깨지면: 제약 설명을 AI 요약 상한(500)보다 짧게 잘라 AI 의 잘림 표시('…')가 빠지거나, 길게 그대로 보내 본문 상한(256KiB)을 채운다
     * (-132 3절 constraints 500 코드 포인트, AI 는 설명을 constraints 에만 쓴다).
     */
    @Test
    void constraintDescriptionCap_staysAboveTheAiSummaryLimit() {
        assertThat(DevelopmentContextComposer.MAX_CONSTRAINT_DESCRIPTION).isGreaterThan(500);
        String cut = DevelopmentContextComposer.cutCodePoints("가".repeat(10_000), DevelopmentContextComposer.MAX_CONSTRAINT_DESCRIPTION);
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(DevelopmentContextComposer.MAX_CONSTRAINT_DESCRIPTION);
    }
}
