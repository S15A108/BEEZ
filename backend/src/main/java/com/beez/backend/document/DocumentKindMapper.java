package com.beez.backend.document;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class DocumentKindMapper implements AttributeConverter<DocumentKind, String> {
    @Override
    public String convertToDatabaseColumn(DocumentKind kind) {
        return kind == null ? null : kind == DocumentKind.REQUIREMENTS ? "REQ_SPEC" : kind.name();
    }

    @Override
    public DocumentKind convertToEntityAttribute(String value) {
        return value == null ? null : "REQ_SPEC".equals(value)
                ? DocumentKind.REQUIREMENTS : DocumentKind.valueOf(value);
    }
}
