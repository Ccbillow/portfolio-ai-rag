package com.simon.rag.service.retrieval.strategy;

import com.simon.rag.service.retrieval.CandidateRetriever;
import com.simon.rag.service.retrieval.CompanyScopeResolver;
import com.simon.rag.service.retrieval.RetrievalContext;
import com.simon.rag.service.retrieval.StrategyResult;
import com.simon.rag.service.retrieval.rerank.CohereRerankService;
import com.simon.rag.service.vectorstore.QdrantSearchService.SearchHit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * FACTUAL_EXPERIENCE — "What was your role at OCBC", "What tech stack at Alipay".
 * Retrieval + rerank but NO minScore cutoff (factual chunks score lower but are still correct).
 */
@Component
@RequiredArgsConstructor
public class FactualExperienceRetrievalStrategy implements RetrievalStrategy {

    private final CandidateRetriever candidateRetriever;
    private final CohereRerankService cohereRerankService;
    private final CompanyScopeResolver companyScopeResolver;

    @Override
    public String name() {
        return "FACTUAL_EXPERIENCE";
    }

    @Override
    public StrategyResult retrieve(RetrievalContext ctx) {
        List<SearchHit> candidates = candidateRetriever.retrieve(
                ctx.queries(), ctx.embeddingCfg(), ctx.focusCompany(), ctx.siblings(), List.of());
        List<SearchHit> scoped = companyScopeResolver.applyCompanyFilter(
                candidates, ctx.focusCompany(), ctx.siblings());
        return new StrategyResult(candidates, scoped,
                cohereRerankService.rerankTopN(ctx.retrievalQuery(), scoped));
    }
}
