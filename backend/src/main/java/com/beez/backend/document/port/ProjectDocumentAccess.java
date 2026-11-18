package com.beez.backend.document.port;

/** Adapter must resolve current session, active membership and LEADER role, and return account ID. */
@FunctionalInterface
public interface ProjectDocumentAccess {
    long requireCurrentHumanMember(long projectId);
}
