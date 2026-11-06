package com.beez.backend.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConflictMeetingBriefTest {

    private static final String SUMMARY = "'A' 와 'B' 가 서로 충돌해요";

    @Test void noMessageFallsBackToTemplate() {
        assertThat(ConflictMeetingListener.brief(SUMMARY, List.of())).isEqualTo(SUMMARY + ". 어느 쪽을 따를지 정해 주세요.");
        assertThat(ConflictMeetingListener.brief(SUMMARY, Arrays.asList(" ", null))).contains("어느 쪽을 따를지");
    }

    @Test void singleMessageIsUsedAsIs() {
        assertThat(ConflictMeetingListener.brief(SUMMARY, List.of("응답 형식이 달라요"))).isEqualTo("응답 형식이 달라요");
    }

    @Test void everyConflictOfThePairIsListedAndDuplicatesAreDropped() {
        String brief = ConflictMeetingListener.brief(SUMMARY, List.of("응답 형식이 달라요", "만료 정책이 달라요", "응답 형식이 달라요"));
        assertThat(brief).isEqualTo(SUMMARY + " (2가지)\n1. 응답 형식이 달라요\n2. 만료 정책이 달라요");
    }
}
