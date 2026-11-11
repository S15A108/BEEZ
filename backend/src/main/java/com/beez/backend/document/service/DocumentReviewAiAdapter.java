package com.beez.backend.document.service;

import com.beez.backend.ai.AiClient;
import com.beez.backend.document.port.DocumentReviewGateway;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 대화(DISCUSS)·직접 요청(REQUEST)은 사용자 요청 안에서 기다리므로 짧은 읽기 제한으로, 첫 검토·재검토(RERUN)는 워커가 부르므로
 * 긴 읽기 제한으로 보낸다(S15P31A108-391). 둘 다 같은 AI 경로이고 모드는 입력의 {@code mode} 로만 갈린다.
 */
@Component
public class DocumentReviewAiAdapter implements DocumentReviewGateway {
    private static final Set<String> CHAT_MODES = Set.of("DISCUSS", "REQUEST");
    private final AiClient ai;

    public DocumentReviewAiAdapter(AiClient ai) { this.ai = ai; }

    @Override
    public Map<String, Object> review(Map<String, Object> input) {
        return CHAT_MODES.contains(input.get("mode")) ? ai.documentReviewChat(input) : ai.documentReview(input);
    }
}
