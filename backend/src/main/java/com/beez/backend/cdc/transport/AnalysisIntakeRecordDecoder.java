package com.beez.backend.cdc.transport;

import com.beez.backend.cdc.intake.AnalysisIntakeChange;

/** Decoder supplied by the eventual #197-specific adapter, not a guessed schema. */
@FunctionalInterface
public interface AnalysisIntakeRecordDecoder {
    /**
     * Decode a non-null Kafka value into a normalized intake change.
     * Malformed or unsupported messages must throw; null is never a successful decode.
     * Implementations must not log the raw key or value.
     */
    AnalysisIntakeChange decode(String key, String value);
}
