package com.simon.rag.eval;

import com.simon.rag.model.eval.EvalCase;

import java.util.ArrayList;
import java.util.List;

public final class ChecksRunner {

    private static final List<String> HARD_STOP_PHRASES = List.of(
            "this taught me", "taught me that", "led me to", "i now treat",
            "going forward", "i learned from this", "lesson learned",
            "reflecting on", "i learned that", "in hindsight",
            "from this experience", "i will always", "since then i");

    private ChecksRunner() {}

    /**
     * Token may contain {@code |}-separated alternatives (OR logic).
     * {@code "legacy | unclear | complex"} matches if any one alternative
     * appears in the answer. Plain tokens match as before (single AND).
     */
    private static boolean matchesOr(String token, String lowerAnswer) {
        if (token.contains("|")) {
            for (String alt : token.split("\\|")) {
                if (lowerAnswer.contains(alt.strip().toLowerCase())) return true;
            }
            return false;
        }
        return lowerAnswer.contains(token.toLowerCase());
    }

    public record ChecksResult(
            List<String> mustContainMissing,
            List<String> mustNotContainHit,
            String hardStopHit,
            int wordCount,
            boolean maxWordsViolated,
            String verdict
    ) {}

    public static ChecksResult run(EvalCase aCase, String answer) {
        String lower = answer == null ? "" : answer.toLowerCase();

        List<String> mcMissing = new ArrayList<>();
        if (aCase.mustContain() != null) {
            for (String token : aCase.mustContain()) {
                if (!matchesOr(token, lower)) mcMissing.add(token.toLowerCase());
            }
        }

        List<String> mncHit = new ArrayList<>();
        if (aCase.mustNotContain() != null) {
            for (String token : aCase.mustNotContain()) {
                if (matchesOr(token, lower)) mncHit.add(token.toLowerCase());
            }
        }

        String hardStop = null;
        if (aCase.checkHardStop()) {
            for (String p : HARD_STOP_PHRASES) {
                if (lower.contains(p)) { hardStop = p; break; }
            }
        }

        int wc = answer == null || answer.isBlank() ? 0 : answer.trim().split("\\s+").length;
        boolean wordsViolated = aCase.maxWords() != null && wc > aCase.maxWords();

        boolean pass = mcMissing.isEmpty() && mncHit.isEmpty()
                && hardStop == null && !wordsViolated;

        return new ChecksResult(mcMissing, mncHit, hardStop, wc, wordsViolated,
                pass ? "PASS" : "FAIL");
    }
}
