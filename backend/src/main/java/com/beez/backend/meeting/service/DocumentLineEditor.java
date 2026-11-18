package com.beez.backend.meeting.service;

import com.beez.backend.common.text.NormalizedText;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 회의 정리(MTG-A11)의 줄 변경을 기준 승인본 원문에 적용한다 (S15P31A108-297, MTG-A13). 부수 효과가 없는 순수 함수다.
 *
 * <p>줄은 {@link NormalizedText#lines} 로 센다 — AI 출력 검증({@code SummaryResultValidator})과 같은 번호다. 정리의
 * {@code before} 는 그 줄 범위의 원문과 정확히 같고 <b>각 줄의 LF 를 포함</b>한다(문서가 LF 로 끝나지 않으면 마지막 줄만 LF 없음).
 * {@code after} 는 그 자리에 들어갈 글이다.
 *
 * <ul>
 *   <li>변경은 줄 번호가 밀리지 않게 뒤에서부터 적용한다.</li>
 *   <li>범위 밖, 서로 겹침, {@code before} 가 지금 원문과 다름은 모두 {@link Conflict} 다 — 정리가 오래됐다는 뜻이다.</li>
 *   <li>{@code after} 가 비어 있으면 그 줄들을 지운다. 여러 줄로 바꾸거나 여러 줄을 한 줄로 바꿀 수 있다.</li>
 *   <li>바뀐 줄 뒤의 줄과 붙지 않도록, 원문에서 LF 로 끝나던 범위의 {@code after} 는 LF 로 끝나게 맞춘다.</li>
 *   <li>결과의 끝 줄바꿈 유무는 원문과 같다: LF 로 끝나던 문서는 LF 로, 아니던 문서는 LF 없이 끝난다. 빈 결과는 빈 문자열이다.</li>
 * </ul>
 */
public final class DocumentLineEditor {
    private DocumentLineEditor() { }

    /** 1-based 닫힌 줄 범위와 그 자리의 원문(before)·새 글(after). */
    public record LineChange(int startLine, int endLine, String before, String after) { }

    /** 정리가 지금 원문과 맞지 않는다. 호출하는 쪽이 {@code summary_outdated} 로 바꾼다. */
    public static final class Conflict extends RuntimeException {
        public Conflict(String message) {
            super(message);
        }
    }

    public static String apply(String baseText, List<LineChange> changes) {
        List<String> lines = NormalizedText.lines(baseText);
        boolean endsWithLf = NormalizedText.endsWithLf(baseText);
        List<LineChange> ordered = new ArrayList<>(changes);
        ordered.sort(Comparator.comparingInt(LineChange::startLine));
        int previousEnd = 0;
        for (LineChange change : ordered) {
            if (change.startLine() < 1 || change.endLine() < change.startLine() || change.endLine() > lines.size()) {
                throw new Conflict("줄 범위가 원문 밖입니다: " + change.startLine() + "-" + change.endLine());
            }
            if (change.startLine() <= previousEnd) {
                throw new Conflict("줄 범위가 겹칩니다: " + change.startLine() + "-" + change.endLine());
            }
            String current = NormalizedText.range(lines, endsWithLf, change.startLine(), change.endLine());
            if (change.before() == null || !current.equals(change.before())) {
                throw new Conflict("원문이 정리 때와 다릅니다: " + change.startLine() + "-" + change.endLine());
            }
            previousEnd = change.endLine();
        }

        // 줄마다 자기 LF 를 붙인 조각으로 나눠 두면 범위 교체가 문자열 이어 붙이기가 된다
        List<String> units = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            units.add(i < lines.size() - 1 || endsWithLf ? lines.get(i) + "\n" : lines.get(i));
        }
        StringBuilder out = new StringBuilder();
        int next = 1;
        for (LineChange change : ordered) {
            appendUnits(out, units, next, change.startLine() - 1);
            out.append(replacement(change, units));
            next = change.endLine() + 1;
        }
        appendUnits(out, units, next, units.size());
        return fixEnding(out.toString(), endsWithLf);
    }

    private static void appendUnits(StringBuilder out, List<String> units, int from, int to) {
        for (int line = from; line <= to; line++) out.append(units.get(line - 1));
    }

    /** 바뀐 범위가 LF 로 끝나던 만큼 새 글도 LF 로 끝나게 한다. 빈 글은 줄을 지운다. */
    private static String replacement(LineChange change, List<String> units) {
        String after = NormalizedText.normalize(change.after());
        if (after.isEmpty()) return "";
        boolean hadLf = units.get(change.endLine() - 1).endsWith("\n");
        if (hadLf && !after.endsWith("\n")) return after + "\n";
        return after;
    }

    /** 원문이 LF 로 끝나지 않았으면 결과도 그렇게, 끝났으면 내용이 있는 결과는 LF 로 끝나게 한다. */
    private static String fixEnding(String text, boolean endsWithLf) {
        if (text.isEmpty()) return text;
        if (endsWithLf) return text.endsWith("\n") ? text : text + "\n";
        return text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
    }
}
