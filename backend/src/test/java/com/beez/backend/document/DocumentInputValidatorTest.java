package com.beez.backend.document;

import static org.assertj.core.api.Assertions.*;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.document.validation.DocumentInputValidator;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DocumentInputValidatorTest {
    private final DocumentInputValidator validator = new DocumentInputValidator(4096, 1000);

    @Test
    void acceptsFourMarkdownKindsAndPlainTextFixturesWithoutStructureRequirements() throws Exception {
        for (String name : new String[] {"plan.md", "requirements.md", "api-spec.md", "data-design.md", "plain.txt"}) {
            try (var fixture = getClass().getResourceAsStream("/document/" + name)) {
                assertThat(fixture).isNotNull();
                assertThat(validator.validate(name, fixture.readAllBytes()).text()).isNotBlank();
            }
        }
    }

    @Test
    void normalizesBomAndLineEndingsButPreservesWhitespaceAndUnicode() {
        var result = validator.validate("../../requirements.MD",
                "\uFEFF# 제목\r\n  조건 😀\r끝  \r\n".getBytes(StandardCharsets.UTF_8));
        assertThat(result.text()).isEqualTo("# 제목\n  조건 😀\n끝  \n");
        assertThat(result.extension()).isEqualTo("md");
        assertThat(result.contentHash()).isNotEqualTo(result.textHash());
        var normalized = validator.validate("input.md", result.text().getBytes(StandardCharsets.UTF_8));
        assertThat(normalized.textHash()).isEqualTo(result.textHash());
        assertThat(normalized.contentHash()).isEqualTo(result.textHash());
    }

    @Test
    void matchesSharedContractHash() {
        var result = validator.validate("requirements.md",
                "# 요구사항\nREQ-01: 로그인한 사용자만 프로젝트를 조회한다.\n".getBytes(StandardCharsets.UTF_8));
        assertThat(result.textHash()).isEqualTo("sha256:cb0951a395a40f48f3eb0cc2a42611e7800168f14da1268091e0d4667604770c");
    }

    @Test
    void txtNeedsNoMarkdownStructureAndCharacterLimitCountsCodePoints() {
        var result = new DocumentInputValidator(100, 2).validate("plain.txt", "가😀".getBytes(StandardCharsets.UTF_8));
        assertThat(result.text()).isEqualTo("가😀");
        assertThat(result.mediaType()).isEqualTo("text/plain");
        assertThatThrownBy(() -> new DocumentInputValidator(100, 1).validate("plain.txt",
                "가😀".getBytes(StandardCharsets.UTF_8))).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsInvalidUtf8BinaryEmptyAndUnsupportedInputs() {
        assertThatThrownBy(() -> validator.validate("bad.md", new byte[] {(byte) 0xc3, 0x28}))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> validator.validate("binary.txt", new byte[] {65, 0, 66}))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> validator.validate("empty.txt", "\uFEFF \r\n".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> validator.validate("source.sql", new byte[] {65}))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("unsupported_media_type"));
        assertThatThrownBy(() -> new DocumentInputValidator(1, 100).validate("large.md", new byte[] {65, 66}))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("file_too_large"));
    }

    @Test
    void convertsAllPublicDocumentKindsToV2AndBack() {
        var mapper = new DocumentKindMapper();
        for (var kind : DocumentKind.values()) {
            assertThat(mapper.convertToEntityAttribute(mapper.convertToDatabaseColumn(kind))).isEqualTo(kind);
        }
        assertThat(mapper.convertToDatabaseColumn(DocumentKind.REQUIREMENTS)).isEqualTo("REQ_SPEC");
    }
}
