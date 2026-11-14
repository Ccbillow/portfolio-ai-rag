package com.simon.rag.service.retrieval;

import com.simon.rag.comm.enums.QuestionType;

import java.util.List;

/**
 * Result of the pre-LLM pipeline (gate checks + retrieval), shared by ask(),
 * askStream(), and the eval path. {@code earlyReturn} is non-null when the
 * request should be answered without hitting the LLM or Qdrant.
 */
public record RetrievalResult(
        QuestionType questionType,
        String earlyReturn,
        String history,
        List<RerankedHit> hits,
        String context,
        String focusCompany,
        long retrieveMs,
        String strategyName,
        /** Pre-final stage snapshots (candidates / reranked) — read by the eval only. */
        StrategyResult stages
) {
    /** Gate rejection — answered rule-based, no retrieval performed. */
    public static RetrievalResult earlyReturn(QuestionType type, String message) {
        return new RetrievalResult(type, message, null, null, null, null, 0, null, null);
    }

    public static RetrievalResult of(QuestionType type, String history, StrategyResult stages,
                                     String context, String focusCompany,
                                     long retrieveMs, String strategyName) {
        return new RetrievalResult(type, null, history, stages.hits(), context, focusCompany,
                retrieveMs, strategyName, stages);
    }

    public boolean hasEarlyReturn() {
        return earlyReturn != null;
    }

    public boolean noHits() {
        return hits != null && hits.isEmpty();
    }
}
