package com.beez.backend.document.entity;

import com.beez.backend.common.entity.BaseTimeEntity;
import com.beez.backend.document.DocumentKind;
import com.beez.backend.document.DocumentKindMapper;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "document")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Document extends BaseTimeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "project_id", nullable = false)
    private Long projectId;
    @Convert(converter = DocumentKindMapper.class)
    @Column(name = "type", nullable = false, columnDefinition = "text")
    private DocumentKind kind;
    @Column(nullable = false, columnDefinition = "text")
    private String title;
    @Column(columnDefinition = "text")
    private String domain;
    @Column(name = "created_by", nullable = false)
    private Long createdBy;
    /** 등록 취소(S15P31A108-382)한 시각. 값이 있으면 목록·종류 중복·기준 생성에서 빠진다. 쓰기는 취소 API 의 SQL 만 한다. */
    @Column(name = "discarded_at", insertable = false, updatable = false)
    private java.time.OffsetDateTime discardedAt;

    public Document(long projectId, DocumentKind kind, String title, String domain, long createdBy) {
        this.projectId = projectId;
        this.kind = kind;
        this.title = title;
        this.domain = domain;
        this.createdBy = createdBy;
    }
}
