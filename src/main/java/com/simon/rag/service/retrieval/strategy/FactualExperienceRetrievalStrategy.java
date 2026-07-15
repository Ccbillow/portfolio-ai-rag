package com.simon.rag.service.retrieval.strategy;

import com.simon.rag.model.eval.RerankedHit;
import com.simon.rag.service.retrieval.CandidateRetriever;
import com.simon.rag.service.retrieval.CompanyScopeResolver;
import com.simon.rag.service.retrieval.RetrievalContext;
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
    public List<RerankedHit> retrieve(RetrievalContext ctx) {
        List<SearchHit> candidates = candidateRetriever.retrieve(
                ctx.queries(), ctx.embeddingCfg(), ctx.focusCompany(), ctx.siblings(), List.of());
        List<RerankedHit> ranked = cohereRerankService.rerankTopN(ctx.retrievalQuery(), candidates);
        return companyScopeResolver.applyCompanyFilter(ranked, ctx.focusCompany(), ctx.siblings());
    }
}
