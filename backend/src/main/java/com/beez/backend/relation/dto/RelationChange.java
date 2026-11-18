package com.beez.backend.relation.dto;

/** 실제로 바뀐 관계 한 쌍 (관계 저장 서비스 반환) */
public record RelationChange(Long historyId, Long fromItemId, Long toItemId, String action, String origin,
                             String status) {
}
