package com.simon.rag.service.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.simon.rag.config.RagProperties;
import com.simon.rag.service.impl.QdrantSearchService.SearchHit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CohereRerankService {

    private final WebClient webClient;
    private final RagProperties ragProperties;
    private final boolean hasKey;

    public CohereRerankService(
            WebClient.Builder webClientBuilder,
            @Value("${cohere.api-key:}") String apiKey,
            RagProperties ragProperties) {
        this.ragProperties = ragProperties;
        this.hasKey = apiKey != null && !apiKey.isBlank();
        this.webClient = webClientBuilder
                .baseUrl("https://api.cohere.com")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
        if (!hasKey) {
            log.warn("COHERE_API_KEY not set — reranker will be skipped");
        }
    }

    /**
     * Reranks candidates using Cohere and returns top-N in relevance order.
     * Falls back to original order (limited to topN) if API is unavailable.
     */
    public List<SearchHit> rerank(String query, List<SearchHit> candidates) {
        RagProperties.Reranker cfg = ragProperties.getReranker();

        if (!hasKey || candidates.isEmpty()) {
            return candidates.stream().limit(cfg.getTopN()).collect(Collectors.toList());
        }

        List<String> documents = candidates.stream()
                .map(SearchHit::text)
                .collect(Collectors.toList());

        try {
            RerankRequest request = new RerankRequest(
                    cfg.getModel(), query, documents, cfg.getTopN(), false);

            RerankResponse response = webClient.post()
                    .uri("/v2/rerank")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(RerankResponse.class)
                    .block();

            if (response == null || response.results() == null || response.results().isEmpty()) {
                log.warn("Cohere returned empty results — falling back to score order");
                return candidates.stream().limit(cfg.getTopN()).collect(Collectors.toList());
            }

            List<SearchHit> reranked = response.results().stream()
                    .map(r -> candidates.get(r.index()))
                    .collect(Collectors.toList());

            log.info("Reranked {}/{} candidates → top-{}: scores {}",
                    candidates.size(), documents.size(), reranked.size(),
                    response.results().stream()
                            .map(r -> String.format("%.3f", r.relevanceScore()))
                            .collect(Collectors.joining(", ")));

            return reranked;

        } catch (Exception e) {
            log.warn("Cohere rerank failed ({}), falling back to score order", e.getMessage());
            return candidates.stream().limit(cfg.getTopN()).collect(Collectors.toList());
        }
    }

    // ---- Request / Response DTOs ----

    private record RerankRequest(
            String model,
            String query,
            List<String> documents,
            @JsonProperty("top_n") int topN,
            @JsonProperty("return_documents") boolean returnDocuments
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RerankResponse(List<RerankResult> results) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RerankResult(
            int index,
            @JsonProperty("relevance_score") double relevanceScore
    ) {}
}
