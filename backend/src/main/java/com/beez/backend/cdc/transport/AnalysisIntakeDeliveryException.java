package com.beez.backend.cdc.transport;

/** A failed delivery remains eligible for the transport error handler to retry. */
public final class AnalysisIntakeDeliveryException extends RuntimeException {
    public AnalysisIntakeDeliveryException() {
        super("CDC intake record could not be processed");
    }
}
