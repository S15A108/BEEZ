package com.beez.backend.document;

import static org.mockito.Mockito.*;

import com.beez.backend.ai.AiClient;
import com.beez.backend.document.service.DocumentReviewAiAdapter;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 첫 검토·재검토는 워커용 긴 제한, 대화·직접 요청은 nginx 안의 짧은 제한으로 보낸다(S15P31A108-391).
 * mode 가 없는 첫 검토 입력에서 NPE 가 나 모든 검토가 실패한 적이 있다(통합 시험은 Gateway 를 대역으로 바꿔 잡지 못했다).
 */
class DocumentReviewAiAdapterTest {
    private final AiClient ai = mock(AiClient.class);
    private final DocumentReviewAiAdapter adapter = new DocumentReviewAiAdapter(ai);

    @Test void firstReviewWithoutModeAndRerunUseTheWorkerClient() {
        adapter.review(new HashMap<>(Map.of("document_id", "1")));
        adapter.review(Map.of("mode", "RERUN"));
        verify(ai, times(2)).documentReview(anyMap());
        verify(ai, never()).documentReviewChat(anyMap());
    }

    @Test void discussAndRequestUseTheShortChatClient() {
        adapter.review(Map.of("mode", "DISCUSS"));
        adapter.review(Map.of("mode", "REQUEST"));
        verify(ai, times(2)).documentReviewChat(anyMap());
        verify(ai, never()).documentReview(anyMap());
    }
}
