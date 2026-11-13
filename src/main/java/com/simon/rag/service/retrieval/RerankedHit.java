package com.simon.rag.service.retrieval;

import com.simon.rag.service.vectorstore.QdrantSearchService.SearchHit;

/**
 * A retrieved chunk together with its post-rerank score. Strategies that skip
 * reranking set {@code rerankScore} to the retrieval score.
 */
public record RerankedHit(SearchHit hit, double rerankScore) {
    public double denseScore() { return hit.score(); }
}
