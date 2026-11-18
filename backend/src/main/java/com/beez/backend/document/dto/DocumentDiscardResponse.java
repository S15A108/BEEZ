package com.beez.backend.document.dto;

/** DOC-A25 문서 등록 취소 결과. 취소한 문서는 목록에서 사라진다. */
public record DocumentDiscardResponse(String documentId, boolean discarded) { }
