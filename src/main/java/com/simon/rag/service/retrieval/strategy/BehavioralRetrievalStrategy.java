package com.simon.rag.service.retrieval.strategy;

import com.simon.rag.comm.enums.IntentTag;
import com.simon.rag.service.retrieval.RerankedHit;
import com.simon.rag.service.retrieval.CandidateRetriever;
import com.simon.rag.service.retrieval.CompanyScopeResolver;
import com.simon.rag.service.retrieval.RetrievalContext;
import com.simon.rag.service.retrieval.rerank.CohereRerankService;
import com.simon.rag.service.vectorstore.QdrantSearchService.SearchHit;
import com.simon.rag.service.retrieval.query.QueryIntentClassifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * BEHAVIORAL / TECHNICAL / STRATEGIC.
 * Intent classification → hybrid retrieval → Cohere rerank (with minScore cutoff).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BehavioralRetrievalStrategy implements RetrievalStrategy {

    private final QueryIntentClassifier queryIntentClassifier;
    private final CandidateRetriever candidateRetriever;
    private final CohereRerankService cohereRerankService;
    private final CompanyScopeResolver companyScopeResolver;

    @Override
    public String name() {
        return "BEHAVIORAL";
    }

    @Override
    public List<RerankedHit> retrieve(RetrievalContext ctx) {
        List<IntentTag> intents = queryIntentClassifier.classify(ctx.question());
        log.info("[Strategy] BEHAVIORAL intents={}", intents);
        List<SearchHit> candidates = candidateRetriever.retrieve(
                ctx.queries(), ctx.embeddingCfg(), ctx.focusCompany(), ctx.siblings(), intents);
        List<RerankedHit> ranked = cohereRerankService.rerank(ctx.retrievalQuery(), candidates);
        return companyScopeResolver.applyCompanyFilter(ranked, ctx.focusCompany(), ctx.siblings());
    }
}
