package com.beez.backend.cdc;

import java.util.UUID;

public record PublicationEvent(UUID eventId, long projectId, long baselineId, String publicationKind, String payloadJson) {}
