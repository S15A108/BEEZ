package com.beez.backend.document.api;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.config.BeezProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Document mutations only. Authentication remains the common CallerContext resolver's responsibility. */
@Component
public class DocumentRequestOriginVerifier {
    private final Set<Origin> allowed;

    public DocumentRequestOriginVerifier(BeezProperties properties) {
        allowed = properties.cors().allowedOrigins().stream()
                .map(value -> origin(value, false)).collect(Collectors.toUnmodifiableSet());
    }

    public void requireTrustedOrigin(HttpServletRequest request) {
        var origins = Collections.list(request.getHeaders("Origin"));
        var referers = Collections.list(request.getHeaders("Referer"));
        // An invalid/present Origin never falls back to a trusted Referer.
        if (origins.size() > 1 || (origins.isEmpty() && referers.size() != 1)) throw rejected();
        String value = origins.isEmpty() ? referers.getFirst() : origins.getFirst();
        try {
            if (!allowed.contains(origin(value, origins.isEmpty()))) throw rejected();
        } catch (IllegalArgumentException e) {
            throw rejected();
        }
    }

    private static Origin origin(String value, boolean referer) {
        URI uri = URI.create(value);
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535
                || (!referer && (uri.getRawQuery() != null || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())))) {
            throw new IllegalArgumentException("Expected an explicit HTTP(S) origin");
        }
        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        int port = uri.getPort() == -1 ? (normalizedScheme.equals("https") ? 443 : 80) : uri.getPort();
        return new Origin(normalizedScheme, uri.getHost().toLowerCase(Locale.ROOT), port);
    }

    private static ApiException rejected() {
        return new ApiException(HttpStatus.FORBIDDEN, "request_origin_rejected", "허용된 화면에서 변경 요청을 보내세요.");
    }

    private record Origin(String scheme, String host, int port) { }
}
