package com.beez.backend.document.dto;

/** DOC-A22 ReviewMessage. {@code proposedText} 는 비비가 다른 대체문을 낸 경우에만 있다. */
public record DocumentMessageResponse(String id, String authorKind, String text, String proposedText, String createdAt) { }
