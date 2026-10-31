package com.beez.backend.document.service;

import com.beez.backend.ai.AiClient;
import com.beez.backend.document.port.DocumentReviewGateway;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DocumentReviewAiAdapter implements DocumentReviewGateway {
    private final AiClient ai;

    public DocumentReviewAiAdapter(AiClient ai) { this.ai = ai; }

    @Override
    public Map<String, Object> review(Map<String, Object> input) { return ai.documentReview(input); }
}
