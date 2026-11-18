package com.beez.backend.meeting.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 비비 회의 정리(AI-A08 SUMMARY) 요청 크기 예산 (application.yml beez.meeting.summary, S15P31A108-235 PR-A §3.4.1).
 * 기록·문서·설계 항목이 하나의 요청 예산을 나눠 쓰며 모든 크기는 <b>바이트</b>다(한글은 글자당 3바이트).
 *
 * @param maxRequestBytes          요청 본문(JSON) 목표 크기. AI 가 model_dump() 로 비어 있는 선택 필드까지 펼쳐 BE 보다 커질 수 있어
 *                                 AI 쪽 한도(1 MiB)보다 약 14% 낮게 잡는다
 * @param maxDocuments             보낼 문서 최대 개수
 * @param maxDocumentBytes         문서 한 건의 원문 최대 바이트. 줄 번호가 원문과 맞아야 해서 문서는 통째로만 보내므로 넘으면 보내지 않는다
 * @param recentEntriesReserveBytes 가장 최근 기록에 먼저 잡아 두는 예산. 문서가 예산을 다 써도 최근 대화는 남는다
 * @param maxEntries               보낼 기록 최대 건수(최근 것부터)
 * @param maxItems                 보낼 설계 항목 최대 개수
 */
@ConfigurationProperties("beez.meeting.summary")
public record MeetingSummaryProperties(
        @DefaultValue("900000") int maxRequestBytes,
        @DefaultValue("10") int maxDocuments,
        @DefaultValue("300000") int maxDocumentBytes,
        @DefaultValue("100000") int recentEntriesReserveBytes,
        @DefaultValue("200") int maxEntries,
        @DefaultValue("100") int maxItems) {

    public MeetingSummaryProperties {
        if (maxRequestBytes < 10_000 || maxDocuments < 0 || maxDocumentBytes < 1 || recentEntriesReserveBytes < 0
                || maxEntries < 1 || maxItems < 0) {
            throw new IllegalArgumentException("beez.meeting.summary: max-request-bytes ≥ 10000, max-documents ≥ 0, "
                    + "max-document-bytes ≥ 1, recent-entries-reserve-bytes ≥ 0, max-entries ≥ 1, max-items ≥ 0");
        }
    }
}
