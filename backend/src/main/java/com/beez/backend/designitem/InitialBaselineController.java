package com.beez.backend.designitem;

import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.api.DocumentRequestOriginVerifier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "Design Items")
public class InitialBaselineController {
    private final BaselinePublishService baselines;
    private final DocumentRequestOriginVerifier origins;
    public InitialBaselineController(BaselinePublishService baselines, DocumentRequestOriginVerifier origins) {
        this.baselines = baselines; this.origins = origins;
    }
    @PostMapping("/api/human/baselines")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "PLN-A10 최초 기준 설계 승인·발행")
    public DesignItemDtos.Baseline publish(CallerContext caller, HttpServletRequest request,
                                          @Valid @RequestBody DesignItemDtos.Publish input,
                                          @RequestHeader("Idempotency-Key") String key) {
        origins.requireTrustedOrigin(request);
        return baselines.publish(caller, input, key);
    }
}
