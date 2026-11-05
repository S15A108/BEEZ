package com.beez.backend.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.text.NormalizedText;
import com.beez.backend.meeting.service.SummaryResultValidator.Context;
import com.beez.backend.meeting.service.SummaryResultValidator.DocInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** AI-A08 SUMMARY 출력 검증 규칙 (S15P31A108-235). 입력으로 보낸 문서·기록·멤버와 대조해 쓸 수 없는 항목을 버린다. */
class SummaryResultValidatorTest {
    static final long DOC = 203, VERSION = 231;
    static final String TEXT = "# 초대\r\n초대는 3일 뒤 만료된다.\r\n링크는 한 번만 쓸 수 있다.\r\n마지막 줄\r\n";

    LongPredicate active = member -> member == 21 || member == 23;
    Context ctx = new Context(Set.of(915L, 917L), Map.of(DOC, new DocInfo(DOC, VERSION, 3, NormalizedText.lines(TEXT))), active);

    Map<String, Object> change(int start, int end, String before, String after) {
        var locator = new LinkedHashMap<String, Object>();
        locator.put("type", "text_lines");
        locator.put("start_line", start);
        locator.put("end_line", end);
        var change = new LinkedHashMap<String, Object>();
        change.put("document_id", "" + DOC);
        change.put("base_version_id", "" + VERSION);
        change.put("locator", locator);
        change.put("before", before);
        change.put("after", after);
        change.put("evidence_entry_ids", List.of("915", "917"));
        return change;
    }

    Map<String, Object> goodChange() {
        return change(2, 2, "초대는 3일 뒤 만료된다.", "초대는 7일 뒤 만료된다.");
    }

    Map<String, Object> resultOf(List<Object> conflicts, List<Object> decisions, List<Object> changes, List<Object> actions) {
        var result = new LinkedHashMap<String, Object>();
        result.put("text", "만료 기간을 7일로 정했다.");
        result.put("conflicts", conflicts);
        result.put("decisions", decisions);
        result.put("document_changes", changes);
        result.put("action_items", actions);
        result.put("unresolved", List.of());
        return result;
    }

    Map<String, Object> response(List<Object> conflicts, List<Object> decisions, List<Object> changes, List<Object> actions) {
        return Map.of("result", resultOf(conflicts, decisions, changes, actions), "origin", "MODEL", "model", "m",
                "usage", List.of(Map.of("model", "m")));
    }

    Map<String, Object> withChanges(Object... changes) {
        return response(List.of("충돌"), List.of("결정"), List.of(changes), List.of());
    }

    void unusable(Map<String, Object> response) {
        assertThatThrownBy(() -> SummaryResultValidator.validate(response, ctx)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(e.getCode()).isEqualTo("ai_unavailable");
        });
    }

    @Test
    void aGoodResponseIsNormalized() {
        var v = SummaryResultValidator.validate(response(List.of(" 충돌 A "),
                List.of("결정 A", Map.of("text", "결정 B", "decided_by_member_id", 21)), List.of(goodChange()),
                List.of(Map.of("text", "상수 수정", "member_id", "23"))), ctx);
        assertThat(v.origin()).isEqualTo("MODEL");
        assertThat(v.text()).isEqualTo("만료 기간을 7일로 정했다.");
        assertThat(v.result().conflicts()).containsExactly("충돌 A");
        assertThat(v.result().decisions()).extracting("text").containsExactly("결정 A", "결정 B");
        assertThat(v.result().decisions()).extracting("decidedByMemberId").containsExactly(null, "21");
        assertThat(v.result().documentChanges()).hasSize(1);
        var c = v.result().documentChanges().get(0);
        assertThat(c.documentId()).isEqualTo("203");
        assertThat(c.baseVersionId()).isEqualTo("231");
        assertThat(c.locator().startLine()).isEqualTo(2);
        assertThat(c.evidenceEntryIds()).containsExactly("915", "917");
        assertThat(v.result().actionItems().get(0).memberId()).isEqualTo("23");
        assertThat(v.unresolved()).isEmpty();
    }

    @Test
    void beforeMustMatchTheLinesExactly() {
        var v = SummaryResultValidator.validate(
                withChanges(goodChange(), change(2, 2, "초대는 3일 뒤 만료된다", "x"), change(3, 3, "다른 문장", "x")), ctx);
        assertThat(v.result().documentChanges()).hasSize(1);
        assertThat(v.unresolved()).anyMatch(s -> s.contains("문서 변경 2건"));
    }

    @Test
    void multiLineRangesJoinWithLfAndCrlfIsNormalized() {
        var v = SummaryResultValidator.validate(
                withChanges(change(2, 3, "초대는 3일 뒤 만료된다.\n링크는 한 번만 쓸 수 있다.", "합친 문장")), ctx);
        assertThat(v.result().documentChanges()).hasSize(1);
        // CRLF 로 이은 before 는 정규화 원문과 다르므로 버리고, 남은 게 없으니 쓸 수 없는 출력이다
        unusable(withChanges(change(2, 3, "초대는 3일 뒤 만료된다.\r\n링크는 한 번만 쓸 수 있다.", "x")));
    }

    @Test
    void rangesOutsideTheDocumentAreDropped() {
        // 문서는 4줄(마지막 줄바꿈 뒤 빈 줄은 줄이 아니다)
        assertThat(NormalizedText.lines(TEXT)).hasSize(4);
        var v = SummaryResultValidator.validate(withChanges(goodChange(), change(4, 5, "마지막 줄\n", "x"),
                change(0, 1, "# 초대", "x"), change(3, 2, "x", "x")), ctx);
        assertThat(v.result().documentChanges()).hasSize(1);
        assertThat(SummaryResultValidator.validate(withChanges(change(4, 4, "마지막 줄", "끝")), ctx).result().documentChanges()).hasSize(1);
    }

    @Test
    void overlappingChangesInTheSameDocumentKeepTheFirst() {
        var v = SummaryResultValidator.validate(withChanges(change(2, 3, "초대는 3일 뒤 만료된다.\n링크는 한 번만 쓸 수 있다.", "a"),
                change(3, 3, "링크는 한 번만 쓸 수 있다.", "b"), change(1, 1, "# 초대", "c")), ctx);
        assertThat(v.result().documentChanges()).extracting("after").containsExactly("a", "c");
        assertThat(v.unresolved()).anyMatch(s -> s.contains("문서 변경 1건"));
    }

    @Test
    void strangeDocumentsVersionsAndEvidenceAreDropped() {
        var otherDoc = goodChange();
        otherDoc.put("document_id", "999");
        var otherVersion = goodChange();
        otherVersion.put("base_version_id", "230");
        var noEvidence = goodChange();
        noEvidence.put("evidence_entry_ids", List.of("1", "2"));
        var halfEvidence = goodChange();
        halfEvidence.put("evidence_entry_ids", List.of("1", "917", 917));
        var badType = goodChange();
        badType.put("locator", Map.of("type", "json_pointer", "start_line", 2, "end_line", 2));
        var noAfter = goodChange();
        noAfter.remove("after");
        var v = SummaryResultValidator.validate(withChanges(goodChange(), otherDoc, otherVersion, noEvidence, halfEvidence, badType, noAfter), ctx);
        // 낯선 문서·버전, 근거 없음, 형식 불량은 버리고 근거가 일부만 맞는 것은 맞는 근거만 남긴다. 줄이 겹치는 건 뒤의 것을 버린다
        assertThat(v.result().documentChanges()).hasSize(1);
        var onlyHalf = SummaryResultValidator.validate(withChanges(halfEvidence), ctx);
        assertThat(onlyHalf.result().documentChanges().get(0).evidenceEntryIds()).containsExactly("917");
    }

    @Test
    void whenEveryChangeIsUnusableTheWholeResponseIsRejected() {
        var otherVersion = goodChange();
        otherVersion.put("base_version_id", "230");
        unusable(withChanges(otherVersion, change(2, 2, "틀린 원문", "x")));
    }

    @Test
    void emptyListsAreFineAndAnEmptyChangeListMeansNoDocumentChange() {
        var v = SummaryResultValidator.validate(response(List.of(), List.of(), List.of(), List.of()), ctx);
        assertThat(v.result().documentChanges()).isEmpty();
        assertThat(v.unresolved()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void structureAndOriginProblemsAreRejected() {
        unusable(Map.of("origin", "MODEL"));
        unusable(Map.of("result", "x", "origin", "MODEL"));
        var r = response(List.of(), List.of(), List.of(), List.of());
        var badOrigin = new LinkedHashMap<>(r);
        badOrigin.put("origin", "GUESS");
        unusable(badOrigin);
        var noOrigin = new LinkedHashMap<>(r);
        noOrigin.remove("origin");
        unusable(noOrigin);
        for (String field : List.of("conflicts", "decisions", "document_changes", "action_items")) {
            var result = new LinkedHashMap<String, Object>((Map<String, Object>) r.get("result"));
            result.remove(field);
            unusable(Map.of("result", result, "origin", "MODEL"));
            result.put(field, "not a list");
            unusable(Map.of("result", result, "origin", "MODEL"));
        }
    }

    @Test
    void actionItemsKeepOnlyActiveMembers() {
        var v = SummaryResultValidator.validate(response(List.of(), List.of(), List.of(), List.of(
                Map.of("text", "a", "member_id", "21"), Map.of("text", "b", "member_id", "99"), Map.of("text", "c"),
                Map.of("text", " ", "member_id", "21"))), ctx);
        assertThat(v.result().actionItems()).extracting("memberId").containsExactly("21", null, null);
        assertThat(v.result().actionItems()).extracting("text").containsExactly("a", "b", "c");
        assertThat(v.unresolved()).anyMatch(s -> s.contains("할 일 1건"));
    }

    @Test
    void blankAndOversizedTextsAreDropped() {
        var v = SummaryResultValidator.validate(response(List.of("ok", " ", "x".repeat(4001), 5), List.of("결정"), List.of(), List.of()), ctx);
        assertThat(v.result().conflicts()).containsExactly("ok");
        assertThat(v.unresolved()).anyMatch(s -> s.contains("충돌 3건"));
        unusable(response(List.of(" ", "x".repeat(4001)), List.of(), List.of(), List.of()));
    }

    @Test
    void listsAreCappedAtOneHundred() {
        List<Object> many = new ArrayList<>();
        for (int i = 0; i < 130; i++) many.add("충돌 " + i);
        var v = SummaryResultValidator.validate(response(many, List.of(), List.of(), List.of()), ctx);
        assertThat(v.result().conflicts()).hasSize(100);
        assertThat(v.unresolved()).anyMatch(s -> s.contains("충돌 30건"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sourcesMustPointAtTheDocumentsThatWereSent() {
        var result = new LinkedHashMap<String, Object>((Map<String, Object>) response(List.of(), List.of(), List.of(), List.of()).get("result"));
        result.put("sources", List.of(
                Map.of("document_id", "203", "document_version_id", "231", "version_no", 3, "location", "L2-L2"),
                Map.of("document_id", 203, "document_version_id", 231, "version_no", 3, "location", "L3"),
                Map.of("document_id", "203", "document_version_id", "230", "version_no", 3, "location", "L2"),
                Map.of("document_id", "203", "document_version_id", "231", "version_no", 2, "location", "L2"),
                Map.of("document_id", "203", "document_version_id", "231", "version_no", 3, "location", " ")));
        var v = SummaryResultValidator.validate(Map.of("result", result, "origin", "TEMPLATE"), ctx);
        assertThat(v.sources()).hasSize(2);
        assertThat(v.sources().get(1)).containsEntry("document_id", "203").containsEntry("document_version_id", "231");
        assertThat(v.unresolved()).anyMatch(s -> s.contains("근거 3건"));
        assertThat(v.origin()).isEqualTo("TEMPLATE");
        assertThat(v.model()).isNull();
    }
}
