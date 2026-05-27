package com.simon.rag.eval;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class DiffEngine {

    private DiffEngine() {}

    public record CaseSnapshot(
            String verdict,
            List<String> mustContainMissing,
            List<String> mustNotContainHit,
            String hardStopHit,
            int wordCount,
            boolean maxWordsViolated,
            String answer,
            String topChunkId
    ) {}

    public record Signal(String label, int severity, String detail) {}

    public static Signal classify(CaseSnapshot base, CaseSnapshot latest) {
        // Severity 5 — verdict flip OR new must_not_contain hit
        if ("PASS".equals(base.verdict()) && "FAIL".equals(latest.verdict())) {
            return new Signal("regression", 5, "verdict PASS→FAIL");
        }
        if (newlyNonEmpty(base.mustNotContainHit(), latest.mustNotContainHit())) {
            return new Signal("regression", 5,
                    "must_not_contain newly hit: " + diff(base.mustNotContainHit(), latest.mustNotContainHit()));
        }

        // Severity 4 — newly missing must_contain
        if (newlyNonEmpty(base.mustContainMissing(), latest.mustContainMissing())) {
            return new Signal("regression", 4,
                    "must_contain newly missing: " + diff(base.mustContainMissing(), latest.mustContainMissing()));
        }

        // Severity 3 — hard_stop OR retrieval_drift
        if (base.hardStopHit() == null && latest.hardStopHit() != null) {
            return new Signal("regression", 3, "hard_stop hit: " + latest.hardStopHit());
        }
        boolean topChanged = !nullSafeEq(base.topChunkId(), latest.topChunkId());
        boolean textChanged = !nullSafeEq(base.answer(), latest.answer());
        if (topChanged && textChanged) {
            return new Signal("retrieval_drift", 3,
                    "top chunk " + base.topChunkId() + " → " + latest.topChunkId());
        }

        // Severity 2 — word count drift > 30%
        if (base.wordCount() > 0) {
            double ratio = Math.abs(latest.wordCount() - base.wordCount()) / (double) base.wordCount();
            if (ratio > 0.30) {
                return new Signal("length_drift", 2,
                        "wordCount " + base.wordCount() + " → " + latest.wordCount());
            }
        }

        // Severity 1 — token-jaccard < 0.6
        if (textChanged) {
            double j = jaccard(tokens(base.answer()), tokens(latest.answer()));
            if (j < 0.6) {
                return new Signal("text_drift", 1, String.format("jaccard=%.2f", j));
            }
        }

        // Improvement (informational)
        if ("FAIL".equals(base.verdict()) && "PASS".equals(latest.verdict())) {
            return new Signal("improvement", 0, "verdict FAIL→PASS");
        }
        return null;
    }

    private static boolean newlyNonEmpty(List<String> baseList, List<String> latestList) {
        if (latestList == null || latestList.isEmpty()) return false;
        if (baseList == null) return true;
        return !new HashSet<>(baseList).containsAll(latestList);
    }

    private static String diff(List<String> base, List<String> latest) {
        Set<String> added = new HashSet<>(latest == null ? List.of() : latest);
        if (base != null) added.removeAll(base);
        return added.toString();
    }

    private static Set<String> tokens(String s) {
        if (s == null || s.isBlank()) return Set.of();
        return new HashSet<>(Arrays.asList(s.toLowerCase().split("\\W+")));
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        Set<String> u = new HashSet<>(a); u.addAll(b);
        Set<String> i = new HashSet<>(a); i.retainAll(b);
        return i.size() / (double) u.size();
    }

    private static boolean nullSafeEq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
