package com.simon.rag.model.eval;

import com.simon.rag.service.impl.QdrantSearchService.SearchHit;

/**
 * Wraps a SearchHit with its post-rerank score so evaluation can observe both.
 * In production paths only {@code hit().text()} is consumed; rerankScore is for eval/logging.
 */
public record RerankedHit(SearchHit hit, double rerankScore) {
    public double denseScore() { return hit.score(); }
}
