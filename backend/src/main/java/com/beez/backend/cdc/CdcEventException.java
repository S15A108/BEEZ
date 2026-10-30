package com.beez.backend.cdc;

/** A malformed or inconsistent event must block acknowledgement, not disappear. */
public class CdcEventException extends RuntimeException {
    public CdcEventException(String message) { super(message); }
}
