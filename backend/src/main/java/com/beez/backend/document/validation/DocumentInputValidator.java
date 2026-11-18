package com.beez.backend.document.validation;

import com.beez.backend.common.api.ApiException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.http.HttpStatus;

/** Limits are explicit constructor inputs until the team agrees on public upload limits. */
public final class DocumentInputValidator {
    private final int maxBytes;
    private final int maxCharacters;

    public DocumentInputValidator(int maxBytes, int maxCharacters) {
        if (maxBytes < 1 || maxCharacters < 1) {
            throw new IllegalArgumentException("Positive upload limits required");
        }
        this.maxBytes = maxBytes;
        this.maxCharacters = maxCharacters;
    }

    public NormalizedDocument validate(String filename, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw invalid("Empty document");
        }
        if (bytes.length > maxBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large", "Document exceeds byte limit");
        }
        String extension = extension(filename);
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw invalid("Document must be valid UTF-8");
        }
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        if (text.isBlank() || text.codePoints().anyMatch(c -> Character.isISOControl(c)
                && c != '\n' && c != '\t')) {
            throw invalid("Document must contain non-empty text without binary control characters");
        }
        if (text.codePointCount(0, text.length()) > maxCharacters) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large", "Document exceeds character limit");
        }
        return new NormalizedDocument(extension, extension.equals("md") ? "text/markdown" : "text/plain",
                text, sha256(bytes), sha256(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String extension(String filename) {
        if (filename == null || filename.indexOf('\0') >= 0) {
            throw invalid("Filename required");
        }
        int dot = filename.lastIndexOf('.');
        String extension = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!extension.equals("md") && !extension.equals("txt")) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type", "Only md/txt documents supported");
        }
        return extension;
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", message);
    }

    private static String sha256(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM SHA-256 unavailable", e);
        }
    }
}
