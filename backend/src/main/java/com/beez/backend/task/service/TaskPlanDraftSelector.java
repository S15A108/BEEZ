package com.beez.backend.task.service;

import com.beez.backend.document.service.DocumentIndexWorker.ChunkEvidence;
import com.beez.backend.task.service.ProjectDesignLookup.ApprovedItem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Local ranking/compression only: no model call, no new facts or source paraphrases. */
public final class TaskPlanDraftSelector {
    private TaskPlanDraftSelector() {}
    private static final Set<String> STOP_WORDS = Set.of(
            "api", "구현", "구현하기", "만들기", "개발", "기능", "작업", "추가", "수정", "the", "and");

    static Set<String> terms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        for (String term : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_/-]+")) {
            if (term.length() >= 2 && !STOP_WORDS.contains(term)) terms.add(term);
        }
        return terms;
    }

    static double lexical(Set<String> terms, String text) {
        if (terms.isEmpty()) return 0;
        String lower = text.toLowerCase(Locale.ROOT);
        return terms.stream().filter(lower::contains).count() / (double) terms.size();
    }

    public static List<ChunkEvidence> excerpts(String query, List<ChunkEvidence> candidates, TaskPlanDraftLimits limits) {
        Set<String> terms = terms(query);
        double best = candidates.stream().map(ChunkEvidence::similarity)
                .filter(s -> s != null && Double.isFinite(s)).mapToDouble(Double::doubleValue).max().orElse(-1);
        // Relative threshold retains semantic matches with no literal keyword.
        var ranked = candidates.stream()
                .filter(c -> c.similarity() == null || Double.isFinite(c.similarity()))
                .filter(c -> c.similarity() == null || c.similarity() >= best - limits.similarityDrop()
                        || lexical(terms, c.text()) > 0)
                .sorted(Comparator.<ChunkEvidence>comparingDouble(c ->
                        (c.similarity() == null ? 0 : c.similarity())
                        + limits.lexicalWeight() * lexical(terms, c.text())).reversed()
                        .thenComparingLong(ChunkEvidence::chunkId)).toList();
        Set<String> seen = new HashSet<>();
        List<ChunkEvidence> selected = new ArrayList<>();
        int total = 0;
        for (ChunkEvidence chunk : ranked) {
            if (chunk.text().length() > limits.maxExcerptChars()) continue;
            String text = compactTable(chunk.text(), terms);
            if (text.isBlank() || text.strip().matches("(?s)#{1,6}[^\\n]*")
                    || !seen.add(text.replaceAll("\\s+", " ").strip())) continue;
            if (total + text.length() > limits.totalExcerptChars()) continue;
            selected.add(new ChunkEvidence(chunk.chunkId(), chunk.documentId(), chunk.documentVersionId(), chunk.versionNo(),
                    chunk.chunkKey(), text, chunk.locator(), chunk.title(), chunk.similarity()));
            total += text.length();
            if (selected.size() >= limits.selectedExcerpts()) break;
        }
        return selected;
    }

    static String compactTable(String text, Set<String> terms) {
        // Only standalone Markdown tables are reduced. Mixed prose/code stays intact.
        List<String> lines = text.lines().filter(line -> !line.isBlank()).toList();
        if (lines.size() < 3 || terms.isEmpty() || lines.stream().anyMatch(l -> !l.strip().startsWith("|"))
                || !lines.get(1).matches("[\\s|:\\-]+")) return text;
        List<String> matching = lines.subList(2, lines.size()).stream()
                .filter(line -> lexical(terms, line) > 0).toList();
        if (matching.isEmpty()) return text; // Semantic-only hit: do not discard its content.
        return lines.get(0) + "\n" + lines.get(1) + "\n" + String.join("\n", matching) + "\n";
    }

    public static List<ApprovedItem> designItems(String query, List<ChunkEvidence> excerpts,
                                                List<ApprovedItem> approved, int limit) {
        Set<String> terms = terms(query);
        String source = String.join("\n", excerpts.stream().map(ChunkEvidence::text).toList());
        // 항목마다 점수를 한 번만 계산한다(정렬 비교기 안에서 다시 계산하지 않는다).
        record Scored(ApprovedItem item, double score) { }
        return approved.stream().map(item -> new Scored(item, relevance(terms, source, item)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingDouble(Scored::score).reversed()
                        .thenComparing(scored -> scored.item().key()))
                .limit(limit).map(Scored::item).toList();
    }

    private static double relevance(Set<String> terms, String source, ApprovedItem item) {
        return lexical(terms, item.title() + " " + item.description()) + (mentionsKey(source, item.key()) ? 1 : 0);
    }

    /** 원문에 설계 항목 키가 다른 글자에 붙지 않고 그대로 나오는가(REQ-1 이 REQ-12 에 걸리지 않게). 정규식을 만들지 않는다. */
    public static boolean mentionsKey(String source, String key) {
        if (key == null || key.isEmpty()) return false;
        for (int from = source.indexOf(key); from >= 0; from = source.indexOf(key, from + 1)) {
            int end = from + key.length();
            boolean startsClean = from == 0 || !isKeyChar(source.charAt(from - 1));
            boolean endsClean = end == source.length() || !isKeyChar(source.charAt(end));
            if (startsClean && endsClean) return true;
        }
        return false;
    }

    private static boolean isKeyChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
    }
}
