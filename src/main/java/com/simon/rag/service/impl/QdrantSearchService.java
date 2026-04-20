package com.simon.rag.service.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class QdrantSearchService {

    private final WebClient qdrantWebClient;

    @Value("${qdrant.collection-name}")
    private String collectionName;

    public record SearchHit(double score, String text, String fileName, String category, String docId) {}

    // ---- Request / Response DTOs ----

    private record SearchRequest(
            float[] vector,
            int limit,
            @JsonProperty("with_payload") boolean withPayload,
            @JsonProperty("with_vector") boolean withVector,
            @JsonProperty("score_threshold") double scoreThreshold
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SearchResponse(List<ScoredPoint> result) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ScoredPoint(double score, Map<String, Object> payload) {}

    // ---- Public API ----

    public List<SearchHit> search(float[] queryVector, int topK, double minScore) {
        SearchRequest body = new SearchRequest(queryVector, topK, true, false, minScore);

        SearchResponse response = qdrantWebClient.post()
                .uri("/collections/{name}/points/search", collectionName)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(SearchResponse.class)
                .block();

        if (response == null || response.result() == null) {
            log.warn("Qdrant returned null response");
            return List.of();
        }

        List<SearchHit> hits = response.result().stream()
                .map(this::toSearchHit)
                .toList();

        log.info("Qdrant search: {} hits (topK={}, minScore={})", hits.size(), topK, minScore);
        return hits;
    }

    private SearchHit toSearchHit(ScoredPoint point) {
        Map<String, Object> p = point.payload();
        return new SearchHit(
                point.score(),
                getString(p, "text_segment"),
                getString(p, "fileName"),
                getString(p, "category"),
                getString(p, "docId")
        );
    }

    private String getString(Map<String, Object> payload, String key) {
        if (payload == null) return null;
        Object v = payload.get(key);
        return v != null ? v.toString() : null;
    }
}
