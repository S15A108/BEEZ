package com.beez.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import com.beez.backend.document.dto.*;
import com.beez.backend.designitem.dto.CreateDesignItemRequest;
import com.beez.backend.designitem.dto.UpdateDesignItemRequest;
import com.beez.backend.designitem.dto.PublishInitialBaselineRequest;
import jakarta.validation.Validation;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocalizedRequestValidationTest {
    @Test void invalidDocumentAndDesignRequestsDoNotExposeEnglishValidationDefaults() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (Object request : List.of(
                    new WorkingCopyUpdateRequest(0, "", ""), new DocumentReviewRequest(0),
                    new SuggestionDecisionRequest(0, "INVALID"), new DocumentSubmissionRequest(0, ""),
                    new DocumentDecisionRequest("INVALID", "x".repeat(10001)),
                    new CreateDesignItemRequest("", 0, null, "invalid", "x".repeat(2001)),
                    new UpdateDesignItemRequest("", "", null, "", true, null, "x".repeat(10001), null),
                    new PublishInitialBaselineRequest("", List.of(""), List.of(), ""))) {
                var violations = validator.validate(request);
                assertThat(violations).isNotEmpty();
                assertThat(violations).allSatisfy(v -> assertThat(v.getMessage()).containsPattern("[가-힣]"));
            }
        }
    }
}
