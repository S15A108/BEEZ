package com.beez.backend.document.entity;

import com.beez.backend.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "document_version")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentVersion extends BaseTimeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "document_id", nullable = false)
    private Long documentId;
    @Column(name = "version_no", nullable = false)
    private int versionNo;
    @Column(nullable = false, columnDefinition = "text")
    private String status;
    @Column(name = "parse_status", nullable = false, columnDefinition = "text")
    private String parseStatus;
    @Column(name = "parse_error", columnDefinition = "text")
    private String parseError;
    @Column(name = "storage_key", nullable = false, columnDefinition = "text")
    private String storageKey;
    @Column(name = "content_hash", nullable = false, columnDefinition = "text")
    private String contentHash;
    @Column(name = "content_text", columnDefinition = "text")
    private String contentText;
    @Column(name = "parent_version_id")
    private Long parentVersionId;
    @Column(name = "change_proposal_id")
    private Long changeProposalId;
    @Column(name = "created_by")
    private Long createdBy;
    @Column(name = "approved_by")
    private Long approvedBy;
    @Column(name = "approved_at")
    private Instant approvedAt;
    @Version @Column(name = "lock_version", nullable = false)
    private int lockVersion;

    public static DocumentVersion initialDraft(long documentId, String storageKey,
            String contentHash, String text, long createdBy) {
        DocumentVersion version = new DocumentVersion();
        version.documentId = documentId;
        version.versionNo = 1;
        version.status = "DRAFT";
        // Existing V2 summary only; this does not mean an AI job has been accepted.
        version.parseStatus = "PENDING";
        version.storageKey = storageKey;
        version.contentHash = contentHash;
        version.contentText = text;
        version.createdBy = createdBy;
        return version;
    }

    public static DocumentVersion draft(long documentId, int versionNo, String storageKey,
            String hash, String text, long actor, long parent) {
        DocumentVersion version = initialDraft(documentId, storageKey, hash, text, actor);
        version.versionNo = versionNo;
        version.parentVersionId = parent;
        return version;
    }
}
