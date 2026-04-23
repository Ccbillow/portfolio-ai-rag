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

    public record SearchHit(double score, String text, String fileName, String category, String docId, int chunkIndex) {}

    // ---- Request / Response DTOs ----

    private record SearchRequest(
            float[] vector,
            int limit,
            @JsonProperty("with_payload") boolean withPayload,
            @JsonProperty("with_vector") boolean withVector,
            @JsonProperty("score_threshold") double scoreThreshold
    ) {}

    private record ScrollRequest(
            @JsonProperty("with_payload") boolean withPayload,
            @JsonProperty("with_vector") boolean withVector,
            int limit,
            @JsonProperty("filter") Map<String, Object> filter
    ) {}

    // Scroll API returns: { "result": { "points": [...] } }
    // (different from Search API which returns: { "result": [...] })
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ScrollResponse(ScrollResult result) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ScrollResult(List<ScoredPoint> points) {}

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

    /**
     * Fetch a specific chunk by docId + chunkIndex via Qdrant scroll + filter.
     * Used to expand a hit to its neighbouring chunks for richer context.
     */
    public List<SearchHit> fetchByDocIdAndChunkIndex(String docId, int chunkIndex) {
        Map<String, Object> filter = Map.of(
                "must", List.of(
                        Map.of("key", "docId",       "match", Map.of("value", docId)),
                        Map.of("key", "chunkIndex",  "match", Map.of("value", String.valueOf(chunkIndex)))
                )
        );
        ScrollRequest body = new ScrollRequest(true, false, 1, filter);

        ScrollResponse response = qdrantWebClient.post()
                .uri("/collections/{name}/points/scroll", collectionName)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(ScrollResponse.class)
                .block();

        if (response == null || response.result() == null || response.result().points() == null) return List.of();
        return response.result().points().stream().map(this::toSearchHit).toList();
    }

    private SearchHit toSearchHit(ScoredPoint point) {
        Map<String, Object> p = point.payload();
        int idx = 0;
        try { idx = Integer.parseInt(getString(p, "chunkIndex")); } catch (Exception ignored) {}
        return new SearchHit(
                point.score(),
                getString(p, "text_segment"),
                getString(p, "fileName"),
                getString(p, "category"),
                getString(p, "docId"),
                idx
        );
    }

    /**
     * Delete all points belonging to a document (matched by docId payload field).
     * Uses Qdrant's POST /points/delete with a filter.
     */
    public void deleteByDocId(String docId) {
        Map<String, Object> body = Map.of(
                "filter", Map.of(
                        "must", List.of(
                                Map.of("key", "docId", "match", Map.of("value", docId))
                        )
                )
        );
        qdrantWebClient.post()
                .uri("/collections/{name}/points/delete", collectionName)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .block();
        log.info("Qdrant: deleted all chunks for docId={}", docId);
    }

    private String getString(Map<String, Object> payload, String key) {
        if (payload == null) return null;
        Object v = payload.get(key);
        return v != null ? v.toString() : null;
    }
}
