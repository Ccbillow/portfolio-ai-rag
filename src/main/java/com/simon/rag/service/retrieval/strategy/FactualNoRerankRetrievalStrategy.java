package com.simon.rag.service.retrieval.strategy;

import com.simon.rag.config.RagProperties;
import com.simon.rag.model.eval.RerankedHit;
import com.simon.rag.service.retrieval.CandidateRetriever;
import com.simon.rag.service.retrieval.RetrievalContext;
import com.simon.rag.service.vectorstore.QdrantSearchService.SearchHit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * FACTUAL_TEMPORAL ("how long at X") and FACTUAL_PROFILE (visa, notice period).
 * No rerank — BM25 keyword matching on timeline/profile chunks is more reliable than
 * Cohere's cross-encoder for factual date/logistics content. Both cases share this behavior.
 */
@Component
@RequiredArgsConstructor
public class FactualNoRerankRetrievalStrategy implements RetrievalStrategy {

    private final CandidateRetriever candidateRetriever;
    private final RagProperties ragProperties;

    @Override
    public String name() {
        return "FACTUAL_NO_RERANK";
    }

    @Override
    public List<RerankedHit> retrieve(RetrievalContext ctx) {
        List<SearchHit> candidates = candidateRetriever.retrieve(
                ctx.queries(), ctx.embeddingCfg(), ctx.focusCompany(), ctx.siblings(), List.of());
        return candidates.stream()
                .map(h -> new RerankedHit(h, h.score()))
                .limit(ragProperties.getReranker().getTopN())
                .collect(Collectors.toList());
    }
}
