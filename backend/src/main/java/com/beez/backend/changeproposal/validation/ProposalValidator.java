package com.beez.backend.changeproposal.validation;

import static com.beez.backend.designitem.support.DesignJson.*;
import com.beez.backend.changeproposal.dto.ProposalDtos.*;
import com.beez.backend.common.api.Ids;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ProposalValidator {
    public void validate(com.beez.backend.changeproposal.dto.PublicationDtos.ApproveRequest input) {
        if (input == null) throw invalid("승인 요청 본문이 필요합니다.");
        requiredId(input.expectedBaseBaselineId(), "expected_base_baseline_id");
        validate(new WithdrawRequest(input.expectedRevision(), input.expectedSnapshotHash()));
    }
    public void validate(Input input, boolean update) {
        if (input == null) throw invalid("변경 제안 본문이 필요합니다.");
        requiredId(input.baseBaselineId(), "base_baseline_id");
        text(input.title(), "제목", 200);
        text(input.reason(), "변경 사유", 10000);
        Ids.parse(input.taskId(), "task_id"); Ids.parse(input.validationId(), "validation_id");
        if (input.previousProposalId() != null) requiredId(input.previousProposalId(), "previous_proposal_id");
        if (update && input.previousProposalId() != null)
            throw invalid("재제출 시 이전 제안 연결을 변경할 수 없습니다.");
        if (input.items() == null || input.documents() == null || input.items().size() > 100
                || input.documents().size() > 4 || input.items().isEmpty() && input.documents().isEmpty()) {
            throw invalid("items·documents 배열이 필요하며 항목 100개·문서 4개 이내에서 하나 이상 변경해야 합니다.");
        }
        if (update && (input.expectedRevision() == null || input.expectedRevision() < 1
                || input.expectedSnapshotHash() == null || !input.expectedSnapshotHash().matches("sha256:[0-9a-f]{64}"))) {
            throw invalid("조회한 expected_revision과 expected_snapshot_hash가 필요합니다.");
        }
        if (!update && (input.expectedRevision() != null || input.expectedSnapshotHash() != null))
            throw invalid("최초 제안에는 수정 기대값을 지정할 수 없습니다.");
        var ids = new HashSet<Long>();
        for (Item item : input.items()) {
            if (item == null || item.operation() == null || !Set.of("CREATE", "UPDATE", "RETIRE").contains(item.operation()))
                throw invalid("변경 항목 operation은 CREATE·UPDATE·RETIRE입니다.");
            if ("CREATE".equals(item.operation())) {
                if (item.designItemId() != null || item.baseDesignItemVersionId() != null || item.kind() == null)
                    throw invalid("CREATE는 kind가 필요하며 기존 항목 ID를 지정할 수 없습니다.");
            } else {
                long id = requiredId(item.designItemId(), "design_item_id");
                requiredId(item.baseDesignItemVersionId(), "base_design_item_version_id");
                if (!ids.add(id)) throw invalid("동일한 설계 항목을 중복 변경할 수 없습니다.");
            }
            if ("RETIRE".equals(item.operation())) {
                if (item.proposedContent() != null || item.schemaVersion() != null)
                    throw invalid("RETIRE에는 변경 후 본문·스키마 버전을 지정할 수 없습니다.");
            } else if (item.schemaVersion() == null || item.proposedContent() == null)
                throw invalid("CREATE·UPDATE는 schema_version과 proposed_content가 필요합니다.");
        }
        ids.clear();
        for (DocumentChange doc : input.documents()) {
            if (doc == null) throw invalid("문서 변경 값이 필요합니다.");
            if (!ids.add(requiredId(doc.documentId(), "document_id"))) throw invalid("동일한 문서를 중복 변경할 수 없습니다.");
            requiredId(doc.baseDocumentVersionId(), "base_document_version_id");
            if ((doc.afterContent() == null) == (doc.proposedDocumentVersionId() == null))
                throw invalid("after_content와 proposed_document_version_id 중 하나만 입력해 주세요.");
            if (doc.afterContent() != null) documentText(doc.afterContent());
            if (doc.proposedDocumentVersionId() != null) requiredId(doc.proposedDocumentVersionId(), "proposed_document_version_id");
        }
        canonical(input); // Enforce bounded total input and JSON depth before any write.
    }

    public static long requiredId(String id, String name) {
        if (id == null || !id.matches("[1-9][0-9]*")) throw invalid(name + "는 양의 정수 문자열이어야 합니다.");
        return Ids.parse(id, name);
    }
    public void validate(WithdrawRequest input) {
        if (input == null || input.expectedRevision() == null || input.expectedRevision() < 1
                || input.expectedSnapshotHash() == null || !input.expectedSnapshotHash().matches("sha256:[0-9a-f]{64}"))
            throw invalid("조회한 expected_revision과 expected_snapshot_hash가 필요합니다.");
    }
    public void validate(RejectRequest input) {
        if (input == null) throw invalid("반려 요청 본문이 필요합니다.");
        text(input.reason(), "반려 사유", 10000);
        validate(new WithdrawRequest(input.expectedRevision(), input.expectedSnapshotHash()));
    }
    public static void documentText(String text) {
        if (text == null || text.isBlank() || text.codePointCount(0, text.length()) > 100_000
                || text.getBytes(StandardCharsets.UTF_8).length > 1_048_576
                || text.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
            throw invalid("문서 본문은 비어 있지 않은 100,000자·1 MiB 이내 텍스트여야 합니다.");
    }
    private void text(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw invalid(field + "는 1~" + max + "자여야 합니다.");
    }
}
