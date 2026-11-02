package com.beez.backend.validation.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 서버용 가짜 의미 분석기. AI 서버에 {@code /v1/validate} 가 붙기 전(S15P31A108-184)까지 개발 서버에서 검증 흐름을
 * 끝까지 돌려 보려고 쓴다. {@code beez.validation.fake-analyzer=true} 일 때만 {@link AiValidationAnalyzer} 대신 등록된다.
 *
 * <p>AI 를 부르지 않고, 요청한 검사를 모두 "검사함, 후보 없음"으로 돌려준다. 그래서 의미 분석이 필요한 검증도 규칙 판정만으로
 * PASS 가 된다 — <b>실제 설계 대조를 하지 않는다.</b> 운영에서는 켜지 않는다. 결과는 model 이름({@link #MODEL})이
 * 검증 요약(summary.model)과 실행 기록(validation_run.analyzer_version)에 남아 실제 분석과 구분된다.
 * 사용량(usage)은 실제 모델 호출이 없으므로 빈 목록이다 (공통 규약: 호출이 없으면 usage 를 만들지 않는다).
 */
@Component
@ConditionalOnProperty(name = "beez.validation.fake-analyzer", havingValue = "true")
public class FakeValidationAnalyzer implements ValidationAnalyzer {

    public static final String MODEL = "fake-analyzer";

    private static final Logger log = LoggerFactory.getLogger(FakeValidationAnalyzer.class);

    public FakeValidationAnalyzer() {
        log.warn("검증 가짜 분석기가 켜져 있습니다 (beez.validation.fake-analyzer=true). 의미 분석 없이 PASS 로 끝납니다 — 운영에서는 끄세요");
    }

    @Override
    public Map<String, Object> analyze(Map<String, Object> request) {
        List<String> checked = new ArrayList<>();
        if (request.get("checks") instanceof List<?> checks) {
            for (Object c : checks) {
                if (c instanceof Map<?, ?> check) {
                    checked.add(String.valueOf(check.get("key")));
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("analysis_status", "COMPLETE");
        result.put("checked_keys", checked);
        result.put("unassessed", List.of());
        result.put("findings", List.of());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("result", result);
        response.put("origin", "MODEL");
        response.put("model", MODEL);
        response.put("usage", List.of());
        log.info("validation {} attempt {}: 가짜 분석기로 검사 {}개를 후보 없이 완료", request.get("validation_id"),
                request.get("attempt"), checked.size());
        return response;
    }
}
