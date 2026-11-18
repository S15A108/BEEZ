package com.beez.backend.activity;

import java.time.Instant;

/**
 * 활동 한 건 (명세 00-common Activity 스키마). ID 는 십진 문자열, 값이 없는 필드는 응답에서 빠진다(전역 non_null).
 *
 * @param actorType      HUMAN·AGENT·BEE·SYSTEM. DB actor_kind
 * @param actorAccountId 사람·에이전트면 행위 계정(에이전트는 토큰 주인). 비비·시스템은 없음
 * @param actorName      사람은 계정 이름, 에이전트는 세션의 토큰 이름(없으면 계정 이름). 비비·시스템은 없음 — 화면이 종류로 그린다
 * @param targetType     주 대상 종류({@link ActivityTargetType}). DB ref_kind
 * @param createdAt      발생 시각. DB occurred_at(실제 저장 시각)
 */
public record ActivityResponse(String id, String type, String actorType, String actorAccountId, String agentSessionId,
                               String actorName, String taskId, String changeProposalId, String baselineId,
                               String targetType, String targetId, String result, Instant createdAt) {
}
