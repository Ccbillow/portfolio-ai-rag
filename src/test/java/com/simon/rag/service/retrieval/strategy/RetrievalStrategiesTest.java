package com.simon.rag.service.retrieval.strategy;

import com.simon.rag.comm.enums.IntentTag;
import com.simon.rag.comm.enums.QuestionType;
import com.simon.rag.config.RagProperties;
import com.simon.rag.service.retrieval.RerankedHit;
import com.simon.rag.service.retrieval.CandidateRetriever;
import com.simon.rag.service.retrieval.CompanyScopeResolver;
import com.simon.rag.service.retrieval.QuerySignalClassifier;
import com.simon.rag.service.retrieval.RetrievalContext;
import com.simon.rag.service.retrieval.query.QueryIntentClassifier;
import com.simon.rag.service.retrieval.rerank.CohereRerankService;
import com.simon.rag.service.vectorstore.QdrantSearchService.SearchHit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RetrievalStrategiesTest {

    private final CandidateRetriever candidateRetriever = mock(CandidateRetriever.class);
    private final CohereRerankService rerankService = mock(CohereRerankService.class);
    private final CompanyScopeResolver scopeResolver = mock(CompanyScopeResolver.class);
    private final QueryIntentClassifier intentClassifier = mock(QueryIntentClassifier.class);
    private final QuerySignalClassifier signals = mock(QuerySignalClassifier.class);

    private final RagProperties.Embedding cfg = new RagProperties.Embedding();
    private final List<String> siblings = List.of("Sanofi");
    private final RetrievalContext ctx = new RetrievalContext(
            QuestionType.BEHAVIORAL, "question", "retrieval query",
            List.of("retrieval query", "variant"), "OCBC", siblings, cfg);

    private static SearchHit hit(int chunkIndex, double score, String... companies) {
        return new SearchHit(score, "text-" + chunkIndex, "file.md", "cat", "doc1", chunkIndex, List.of(companies));
    }

    // ── BEHAVIORAL ───────────────────────────────────────────────────

    @Test
    void behavioral_usesIntents_reranksWithCutoff_thenFiltersByCompany() {
        List<IntentTag> intents = List.of(IntentTag.LEADERSHIP);
        List<SearchHit> candidates = List.of(hit(0, 0.9, "OCBC"), hit(1, 0.8, "OCBC"));
        List<RerankedHit> ranked = List.of(new RerankedHit(candidates.get(0), 0.7));
        List<RerankedHit> filtered = List.of(new RerankedHit(candidates.get(0), 0.7));
        when(intentClassifier.classify("question")).thenReturn(intents);
        when(candidateRetriever.retrieve(ctx.queries(), cfg, "OCBC", siblings, intents)).thenReturn(candidates);
        when(rerankService.rerank("retrieval query", candidates)).thenReturn(ranked);
        when(scopeResolver.applyCompanyFilter(ranked, "OCBC", siblings)).thenReturn(filtered);

        var strategy = new BehavioralRetrievalStrategy(
                intentClassifier, candidateRetriever, rerankService, scopeResolver);

        assertEquals("BEHAVIORAL", strategy.name());
        assertSame(filtered, strategy.retrieve(ctx));
        verify(rerankService, never()).rerankTopN(any(), any());
    }

    // ── FACTUAL_EXPERIENCE ───────────────────────────────────────────

    @Test
    void factualExperience_noIntents_reranksWithoutCutoff_thenFiltersByCompany() {
        List<SearchHit> candidates = List.of(hit(0, 0.9, "OCBC"));
        List<RerankedHit> ranked = List.of(new RerankedHit(candidates.get(0), 0.05));
        when(candidateRetriever.retrieve(ctx.queries(), cfg, "OCBC", siblings, List.of())).thenReturn(candidates);
        when(rerankService.rerankTopN("retrieval query", candidates)).thenReturn(ranked);
        when(scopeResolver.applyCompanyFilter(ranked, "OCBC", siblings)).thenReturn(ranked);

        var strategy = new FactualExperienceRetrievalStrategy(candidateRetriever, rerankService, scopeResolver);

        assertEquals("FACTUAL_EXPERIENCE", strategy.name());
        assertSame(ranked, strategy.retrieve(ctx));
        verify(rerankService, never()).rerank(any(), any());
    }

    // ── FACTUAL_NO_RERANK ────────────────────────────────────────────

    @Test
    void factualNoRerank_neverCallsReranker_keepsOrder_truncatesToTopN() {
        RagProperties props = new RagProperties();          // reranker.topN defaults to 5
        List<SearchHit> candidates = IntStream.range(0, 7)
                .mapToObj(i -> hit(i, 1.0 - i * 0.1, "OCBC")).toList();
        when(candidateRetriever.retrieve(ctx.queries(), cfg, "OCBC", siblings, List.of())).thenReturn(candidates);

        var strategy = new FactualNoRerankRetrievalStrategy(candidateRetriever, props);
        List<RerankedHit> out = strategy.retrieve(ctx);

        assertEquals("FACTUAL_NO_RERANK", strategy.name());
        assertEquals(5, out.size());
        assertEquals(0, out.get(0).hit().chunkIndex());
        assertEquals(4, out.get(4).hit().chunkIndex());
        assertEquals(out.get(0).hit().score(), out.get(0).rerankScore(), 1e-9);
        verifyNoInteractions(rerankService);
    }

    // ── MULTI_COMPANY ────────────────────────────────────────────────

    private MultiCompanyRetrievalStrategy multiCompany() {
        RagProperties props = new RagProperties();
        props.setCompanies(List.of("Alipay", "Sinosig"));
        return new MultiCompanyRetrievalStrategy(candidateRetriever, intentClassifier, signals, props);
    }

    @Test
    void multiCompany_nonBehavioral_noIntents_returnsAllCandidatesUnreranked() {
        List<SearchHit> candidates = List.of(hit(0, 0.9, "Alipay"), hit(1, 0.8, "Sinosig"));
        when(signals.isBehavioralThemed("question")).thenReturn(false);
        when(candidateRetriever.retrievePerCompany("retrieval query", cfg, List.of())).thenReturn(candidates);

        var strategy = multiCompany();
        List<RerankedHit> out = strategy.retrieve(ctx);

        assertEquals("MULTI_COMPANY", strategy.name());
        assertEquals(2, out.size());
        assertEquals(0.9, out.get(0).rerankScore(), 1e-9);
        verifyNoInteractions(intentClassifier, rerankService);
    }

    @Test
    void multiCompany_behavioralThemed_passesIntentsToPerCompanyRetrieval() {
        List<IntentTag> intents = List.of(IntentTag.CONFLICT);
        when(signals.isBehavioralThemed("question")).thenReturn(true);
        when(intentClassifier.classify("question")).thenReturn(intents);
        when(candidateRetriever.retrievePerCompany("retrieval query", cfg, intents))
                .thenReturn(List.of(hit(0, 0.9, "Alipay")));

        List<RerankedHit> out = multiCompany().retrieve(ctx);

        // Current behavior: a company with no candidate (Sinosig) is simply absent.
        assertEquals(1, out.size());
        assertEquals(List.of("Alipay"), out.get(0).hit().companies());
    }
}
