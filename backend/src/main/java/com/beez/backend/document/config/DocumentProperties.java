package com.beez.backend.document.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("beez.document")
public record DocumentProperties(boolean workerEnabled, long pollMillis,
        String embeddingProfile, String embeddingModel, int embeddingDimension) {
    public DocumentProperties {
        if (pollMillis < 100 || embeddingDimension != 1024) {
            throw new IllegalArgumentException("Document poll interval and V2 vector(1024) dimension required");
        }
    }
}
