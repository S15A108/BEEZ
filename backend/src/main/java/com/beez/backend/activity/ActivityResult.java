package com.beez.backend.activity;

/** 활동 결과 (계약 4절). 결과가 갈리는 활동(검증 끝남 등)만 {@link #FAILURE} 를 쓴다. DB {@code activity_event.result} */
public enum ActivityResult {
    SUCCESS,
    FAILURE
}
