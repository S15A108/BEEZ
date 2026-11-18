package com.beez.backend.document.port;

import java.util.Map;

/** AI owns inference. BE supplies authorized snapshots and validates the returned suggestions. */
public interface DocumentReviewGateway {
    Map<String, Object> review(Map<String, Object> input);
}
