package com.beez.backend.validation.api;

import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.validation.service.ValidationRequestService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Validation submission and retrieval. The actual AI work runs in the durable worker. */
@RestController
@RequestMapping("/api/projects/{projectId}")
public class ValidationController {
    private final ValidationRequestService service;
    public ValidationController(ValidationRequestService service) { this.service = service; }

    public record StartRequest(@NotBlank String planRevisionId, @NotNull @Min(0) Integer expectedLockVersion) {}
    public record RetryRequest(@NotNull @Min(1) Integer expectedAttempt) {}

    @PostMapping("/tasks/{taskId}/validations")
    public ResponseEntity<Map<String, Object>> start(CallerContext caller, @PathVariable long projectId,
            @PathVariable long taskId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody StartRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.start(caller, projectId, taskId,
                request.planRevisionId(), request.expectedLockVersion(), key));
    }

    @GetMapping("/validations/{validationId}")
    public Map<String, Object> get(CallerContext caller, @PathVariable long projectId,
                                   @PathVariable long validationId) {
        return service.get(caller, projectId, validationId);
    }

    @PostMapping("/validations/{validationId}/retry")
    public ResponseEntity<Map<String, Object>> retry(CallerContext caller, @PathVariable long projectId,
            @PathVariable long validationId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody RetryRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.retry(caller, projectId, validationId,
                request.expectedAttempt(), key));
    }
}
