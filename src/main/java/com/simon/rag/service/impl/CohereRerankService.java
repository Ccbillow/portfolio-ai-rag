package com.simon.rag.service.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.simon.rag.config.RagProperties;
import com.simon.rag.model.eval.RerankedHit;
import com.simon.rag.service.impl.QdrantSearchService.SearchHit;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CohereRerankService {

    private final WebClient webClient;
    private final RagProperties ragProperties;
    private final boolean hasKey;
    private final AtomicLong lastCallTime = new AtomicLong(0);

    public CohereRerankService(
            WebClient.Builder webClientBuilder,
            @Value("${cohere.api-key:}") String apiKey,
            RagProperties ragProperties) {
        this.ragProperties = ragProperties;
        this.hasKey = apiKey != null && !apiKey.isBlank();
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .doOnConnected(conn -> conn.addHandlerLast(
                        new ReadTimeoutHandler(15, TimeUnit.SECONDS)));
        this.webClient = webClientBuilder
                .baseUrl("https://api.cohere.com")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
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
    public List<RerankedHit> rerank(String query, List<SearchHit> candidates) {
        RagProperties.Reranker cfg = ragProperties.getReranker();

        if (!hasKey || candidates.isEmpty()) {
            return candidates.stream().limit(cfg.getTopN())
                    .map(h -> new RerankedHit(h, h.score()))
                    .collect(Collectors.toList());
        }

        List<String> documents = candidates.stream()
                .map(SearchHit::text)
                .collect(Collectors.toList());

        try {
            throttle(cfg.getRateLimitMs());

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
                return candidates.stream().limit(cfg.getTopN())
                        .map(h -> new RerankedHit(h, h.score()))
                        .collect(Collectors.toList());
            }

            double minScore = cfg.getMinRerankScore();
            List<RerankResult> results = response.results();

            List<RerankResult> kept = results.stream()
                    .filter(r -> r.relevanceScore() >= minScore)
                    .collect(Collectors.toList());

            if (kept.isEmpty()) kept = results.subList(0, 1);

            List<RerankedHit> reranked = kept.stream()
                    .map(r -> new RerankedHit(candidates.get(r.index()), r.relevanceScore()))
                    .collect(Collectors.toList());

            log.info("Reranked {}/{} candidates → kept {}/{} (minScore={}): scores {}",
                    candidates.size(), documents.size(),
                    kept.size(), results.size(), minScore,
                    results.stream()
                            .map(r -> String.format("%.3f", r.relevanceScore()))
                            .collect(Collectors.joining(", ")));

            return reranked;

        } catch (Exception e) {
            log.warn("Cohere rerank failed ({}), falling back to score order", e.getMessage());
            return candidates.stream().limit(cfg.getTopN())
                    .map(h -> new RerankedHit(h, h.score()))
                    .collect(Collectors.toList());
        }
    }

    // ---- Request / Response DTOs ----

    /**
     * Rate-limits Cohere API calls. Each call is spaced by at least {@code minGapMs}
     * milliseconds to stay under the free-tier limit (100 req/min → 600 ms gap).
     */
    private void throttle(long minGapMs) {
        while (true) {
            long prev = lastCallTime.get();
            long now = System.currentTimeMillis();
            long next = Math.max(now, prev + minGapMs);
            if (lastCallTime.compareAndSet(prev, next)) {
                long waitMs = next - now;
                if (waitMs > 0) {
                    try { Thread.sleep(waitMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                return;
            }
        }
    }

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
