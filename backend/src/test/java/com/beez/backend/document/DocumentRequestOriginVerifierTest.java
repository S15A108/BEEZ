package com.beez.backend.document;

import static org.assertj.core.api.Assertions.*;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.config.BeezProperties;
import com.beez.backend.document.api.DocumentRequestOriginVerifier;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class DocumentRequestOriginVerifierTest {
    private final DocumentRequestOriginVerifier verifier = new DocumentRequestOriginVerifier(
            new BeezProperties(new BeezProperties.Cors(List.of("https://app.beez.test")), null, null));

    @Test void acceptsTrustedOriginWithoutToken() {
        var request = request("Origin", "https://APP.beez.test:443");
        assertThatCode(() -> verifier.requireTrustedOrigin(request)).doesNotThrowAnyException();
    }

    @Test void acceptsRefererOnlyWhenOriginIsAbsent() {
        assertThatCode(() -> verifier.requireTrustedOrigin(request("Referer", "https://app.beez.test/documents?q=1")))
                .doesNotThrowAnyException();
    }

    @Test void rejectsMissingSource() { reject(new MockHttpServletRequest()); }

    @Test void neverFallsBackFromAnInvalidOrigin() {
        var request = request("Origin", "null");
        request.addHeader("Referer", "https://app.beez.test/documents");
        reject(request);
    }

    @Test void rejectsUntrustedAndMalformedOrigins() {
        for (String origin : List.of("https://evil.test", "https://app.beez.test.evil.test", "http://app.beez.test",
                "https://app.beez.test:444", "https://app.beez.test/", "https://app.beez.test?x=1",
                "https://app.beez.test#x", "https://app.beez.test@evil.test", "https://user@app.beez.test",
                "https://app.beez.test https://evil.test", "", "null")) {
            reject(request("Origin", origin));
        }
    }

    @Test void rejectsDuplicateHeaders() {
        var request = request("Origin", "https://app.beez.test");
        request.addHeader("Origin", "https://evil.test");
        reject(request);
        request = request("Referer", "https://app.beez.test/documents");
        request.addHeader("Referer", "https://app.beez.test/another");
        reject(request);
    }

    @Test void validatesRefererAuthorityWithoutTrustingPathOrUserInfo() {
        reject(request("Referer", "https://evil.test/https://app.beez.test"));
        reject(request("Referer", "https://user@app.beez.test/documents"));
    }

    @Test void rejectsWildcardConfiguration() {
        assertThatThrownBy(() -> new DocumentRequestOriginVerifier(
                new BeezProperties(new BeezProperties.Cors(List.of("*")), null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private MockHttpServletRequest request(String name, String value) {
        var request = new MockHttpServletRequest();
        request.addHeader(name, value);
        return request;
    }

    private void reject(MockHttpServletRequest request) {
        assertThatThrownBy(() -> verifier.requireTrustedOrigin(request))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(error.getCode()).isEqualTo("request_origin_rejected");
                });
    }
}
