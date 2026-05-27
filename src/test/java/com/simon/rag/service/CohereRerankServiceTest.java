package com.simon.rag.service;

import com.simon.rag.model.eval.RerankedHit;
import com.simon.rag.service.impl.CohereRerankService;
import com.simon.rag.service.impl.QdrantSearchService.SearchHit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CohereRerankServiceTest {

    @Test
    void rerank_withoutApiKey_returnsRerankedHitsInOriginalOrder() {
        CohereRerankService svc = newServiceWithoutKey();
        List<SearchHit> input = List.of(
                hit("a", 0.9), hit("b", 0.8), hit("c", 0.7));

        List<RerankedHit> out = svc.rerank("q", input);

        assertEquals(3, out.size(), "fallback returns up to topN");
        assertEquals("a", out.get(0).hit().fileName());
        assertEquals(0.9, out.get(0).rerankScore(), 0.001);
    }

    private static SearchHit hit(String name, double score) {
        return new SearchHit(score, "txt-" + name, name, "cat", "doc1", 0, List.of());
    }

    private static CohereRerankService newServiceWithoutKey() {
        return new CohereRerankService(
                org.springframework.web.reactive.function.client.WebClient.builder(),
                "",
                stubProps());
    }

    private static com.simon.rag.config.RagProperties stubProps() {
        var p = new com.simon.rag.config.RagProperties();
        return p;
    }
}
