package com.simon.rag.service.retrieval;

import com.simon.rag.comm.enums.IntentTag;
import com.simon.rag.config.RagProperties;
import com.simon.rag.service.vectorstore.HybridSearcher;
import com.simon.rag.service.vectorstore.HybridSearcher.QueryVectors;
import com.simon.rag.service.vectorstore.QdrantSearchService.SearchHit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Single responsibility: turn a set of queries into a candidate pool of {@link SearchHit}.
 * Pooling policy only (fetch limit, multi-query merge, per-company fan-out) — the
 * per-query search decision (hybrid vs dense) lives in {@link HybridSearcher}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CandidateRetriever {

    private final HybridSearcher hybridSearcher;
    private final RagProperties ragProperties;

    /**
     * Candidate gathering across all query variants, merged by (docId:chunkIndex),
     * first occurrence wins. Dense-only mode additionally ranks by cosine score and
     * caps at the fetch limit (hybrid mode preserves RRF order as-is).
     */
    public List<SearchHit> retrieve(List<String> queries, RagProperties.Embedding cfg,
                                    String focusCompany, List<String> siblings,
                                    List<IntentTag> intentTags) {
        RagProperties.Reranker rerankCfg = ragProperties.getReranker();
        int fetchLimit = rerankCfg.isEnabled() ? rerankCfg.getCandidateK() : cfg.getTopK();

        LinkedHashMap<String, SearchHit> seen = new LinkedHashMap<>();
        for (String q : queries) {
            hybridSearcher.search(q, fetchLimit, focusCompany, intentTags)
                    .forEach(h -> seen.putIfAbsent(h.dedupeKey(), h));
        }

        if (!ragProperties.getHybridSearch().isEnabled()) {
            return seen.values().stream()
                    .sorted(Comparator.comparingDouble(SearchHit::score).reversed())
                    .limit(fetchLimit)
                    .toList();
        }
        return new ArrayList<>(seen.values());
    }

    /**
     * For multi-company questions: one targeted search per known company (2 chunks each),
     * guaranteeing every company is represented in the candidate pool.
     * The query is vectorized once and reused across companies.
     */
    public List<SearchHit> retrievePerCompany(String primaryQuery, RagProperties.Embedding cfg,
                                              List<IntentTag> intentTags) {
        int perCompanyLimit = 2;
        QueryVectors qv = hybridSearcher.vectorize(primaryQuery);

        LinkedHashMap<String, SearchHit> seen = new LinkedHashMap<>();
        for (String company : ragProperties.getCompanies()) {
            hybridSearcher.search(qv, perCompanyLimit, company, intentTags)
                    .forEach(h -> seen.putIfAbsent(h.dedupeKey(), h));
        }

        log.info("[MultiCompany] Per-company retrieval: {} candidates across {} companies",
                seen.size(), ragProperties.getCompanies().size());
        return new ArrayList<>(seen.values());
    }
}
