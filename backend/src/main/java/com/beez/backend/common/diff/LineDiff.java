package com.beez.backend.common.diff;

import com.beez.backend.common.api.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;

/** Exact line comparison; bounded LCS memory, with a complete replacement fallback. */
public final class LineDiff {
    private static final int MAX_CHARACTERS = 1_048_576;
    private static final int MAX_LINES = 50_000;
    private static final long MAX_CELLS = 1_000_000;

    public static String compare(String before, String after) {
        Objects.requireNonNull(before);
        Objects.requireNonNull(after);
        if (before.length() > MAX_CHARACTERS || after.length() > MAX_CHARACTERS) throw tooLarge();
        if (before.equals(after)) return "";
        List<String> a = lines(before), b = lines(after);
        int prefix = 0;
        while (prefix < Math.min(a.size(), b.size()) && a.get(prefix).equals(b.get(prefix))) prefix++;
        int aEnd = a.size(), bEnd = b.size();
        while (aEnd > prefix && bEnd > prefix && a.get(aEnd - 1).equals(b.get(bEnd - 1))) { aEnd--; bEnd--; }
        var out = new StringBuilder("--- base\n+++ proposed\n");
        out.append("@@ -").append(a.isEmpty() ? 0 : 1).append(',').append(a.size())
                .append(" +").append(b.isEmpty() ? 0 : 1).append(',').append(b.size()).append(" @@\n");
        for (int i = 0; i < prefix; i++) append(out, ' ', a.get(i));
        int n = aEnd - prefix, m = bEnd - prefix;
        if ((long) (n + 1) * (m + 1) > MAX_CELLS) {
            // Still an exact patch: never truncate or claim that unmatched lines are unchanged.
            for (int i = prefix; i < aEnd; i++) append(out, '-', a.get(i));
            for (int j = prefix; j < bEnd; j++) append(out, '+', b.get(j));
        } else {
            int[][] lcs = new int[n + 1][m + 1];
            for (int i = n - 1; i >= 0; i--) for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a.get(prefix + i).equals(b.get(prefix + j))
                        ? 1 + lcs[i + 1][j + 1] : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
            int i = 0, j = 0;
            while (i < n || j < m) {
                if (i < n && j < m && a.get(prefix + i).equals(b.get(prefix + j))) {
                    append(out, ' ', a.get(prefix + i++)); j++;
                } else if (i < n && (j == m || lcs[i + 1][j] >= lcs[i][j + 1])) {
                    append(out, '-', a.get(prefix + i++));
                } else append(out, '+', b.get(prefix + j++));
            }
        }
        for (int i = aEnd; i < a.size(); i++) append(out, ' ', a.get(i));
        return out.toString();
    }

    private static List<String> lines(String text) {
        var result = new ArrayList<String>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') {
            result.add(text.substring(start, i + 1)); start = i + 1;
            if (result.size() > MAX_LINES) throw tooLarge();
        }
        if (start < text.length()) result.add(text.substring(start));
        if (result.size() > MAX_LINES) throw tooLarge();
        return result;
    }

    private static void append(StringBuilder out, char operation, String line) {
        out.append(operation).append(line);
        if (!line.endsWith("\n")) out.append("\n\\ No newline at end of file\n");
    }

    private static ApiException tooLarge() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "diff_too_large",
                "비교할 본문은 각각 1,048,576자·50,000줄 이하여야 합니다.");
    }

    private LineDiff() { }
}
