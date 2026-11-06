package com.beez.backend.meeting.dto;

import java.util.List;
import java.util.Map;

/** 00-common Generation. sources 는 이 요청에 실은 문서·버전만 남기고, usage 는 AI 가 준 줄을 그대로 싣는다 */
public record MeetingGeneration(String origin, String model, String text, List<Map<String, Object>> sources,
                                List<String> unresolved, List<Map<String, Object>> usage) {
}
