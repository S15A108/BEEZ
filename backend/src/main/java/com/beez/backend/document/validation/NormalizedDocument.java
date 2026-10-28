package com.beez.backend.document.validation;

/** contentHash describes original bytes; textHash describes normalized UTF-8 text. */
public record NormalizedDocument(
        String extension, String mediaType, String text, String contentHash, String textHash) {
}
