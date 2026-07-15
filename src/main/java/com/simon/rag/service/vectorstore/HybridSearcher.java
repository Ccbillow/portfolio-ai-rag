package com.simon.rag.service.vectorstore;

import com.simon.rag.comm.enums.IntentTag;
import com.simon.rag.config.RagProperties;
import com.simon.rag.service.vectorstore.QdrantSearchService.SearchHit;
import com.simon.rag.service.vectorstore.SparseVectorizer.SparseVector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * One place that owns the "query text → search hits" decision:
 * embed dense, vectorize sparse, then route to hybrid (RRF) or dense-only search
 * depending on config and sparse availability. Previously this decision was
 * duplicated across CandidateRetriever's multi-query loop and per-company loop.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HybridSearcher {

    private final EmbeddingService embeddingService;
    private final SparseVectorizer sparseVectorizer;
    private final QdrantSearchService qdrantSearchService;
    private final RagProperties ragProperties;

    /** Precomputed query vectors — reuse across multiple searches of the same query. */
    public record QueryVectors(String query, float[] dense, SparseVector sparse) {}

    public QueryVectors vectorize(String query) {
        return new QueryVectors(query, embeddingService.embed(query), sparseVectorizer.vectorize(query));
    }

    public List<SearchHit> search(String query, int limit, String company, List<IntentTag> intentTags) {
        return search(vectorize(query), limit, company, intentTags);
    }

    public List<SearchHit> search(QueryVectors qv, int limit, String company, List<IntentTag> intentTags) {
        boolean hybridEnabled = ragProperties.getHybridSearch().isEnabled();
        if (hybridEnabled && !qv.sparse().isEmpty()) {
            return qdrantSearchService.hybridSearchWithIntentFilter(
                    qv.dense(), qv.sparse(), limit, company, intentTags);
        }
        if (hybridEnabled) {
            log.warn("Sparse vector empty for query '{}', using dense only", qv.query());
        }
        return qdrantSearchService.search(
                qv.dense(), limit, ragProperties.getEmbedding().getMinScore(), company);
    }
}
