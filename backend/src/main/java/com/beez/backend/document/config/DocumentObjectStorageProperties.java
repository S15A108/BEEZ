package com.beez.backend.document.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("beez.document.object-storage")
public record DocumentObjectStorageProperties(URI endpoint, String region,
        String bucket, String accessKey, String secretKey) {
    public DocumentObjectStorageProperties {
        if (endpoint == null || !("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
                || endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null
                || endpoint.getFragment() != null || !(endpoint.getPath().isEmpty() || "/".equals(endpoint.getPath()))
                || region == null || region.isBlank() || bucket == null
                || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
                || accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("SeaweedFS endpoint, region, bucket and credentials required");
        }
    }

    @Override public String toString() {
        return "DocumentObjectStorageProperties[SeaweedFS, credentials=REDACTED]";
    }
}
