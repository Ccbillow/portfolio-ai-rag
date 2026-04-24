package com.simon.rag.service.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.simon.rag.service.impl.SparseVectorizer.SparseVector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class QdrantSearchService {

    private final WebClient qdrantWebClient;

    @Value("${qdrant.collection-name}")
    private String collectionName;

    public record SearchHit(double score, String text, String fileName, String category, String docId, int chunkIndex) {}

    // ---- Ingestion ----

    /**
     * Upserts points with both named dense and sparse vectors.
     * Replaces LangChain4j EmbeddingStore.addAll() so we can store two vector types.
     */
    public void upsertPoints(List<PointData> points) {
        if (points.isEmpty()) return;
        Map<String, Object> body = Map.of("points", points);
        qdrantWebClient.put()
                .uri("/collections/{name}/points", collectionName)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .block();
        log.info("Upserted {} points to Qdrant", points.size());
    }

    public record PointData(
            String id,
            Map<String, Object> vectors,
            Map<String, Object> payload
    ) {}

    // ---- Search ----

    /**
     * Dense-only vector search using named "dense" vector.
     */
    public List<SearchHit> search(float[] queryVector, int topK, double minScore) {
        Map<String, Object> body = Map.of(
                "vector", Map.of("name", "dense", "vector", queryVector),
                "limit", topK,
                "with_payload", true,
                "with_vector", false,
                "score_threshold", minScore
        );
        SearchResponse response = qdrantWebClient.post()
                .uri("/collections/{name}/points/search", collectionName)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(SearchResponse.class)
                .block();

        if (response == null || response.result() == null) {
            log.warn("Qdrant dense search returned null");
            return List.of();
        }
        List<SearchHit> hits = response.result().stream().map(this::toSearchHit).toList();
        log.info("Dense search: {} hits (topK={}, minScore={})", hits.size(), topK, minScore);
        return hits;
    }

    /**
     * Hybrid search: dense + BM25 sparse, fused with RRF via Qdrant Query API.
     * Returns RRF-ranked results — do NOT apply cosine min-score threshold here.
     */
    public List<SearchHit> hybridSearch(float[] denseVector, SparseVector sparseVec, int limit) {
        int prefetchLimit = limit * 2;

        List<Map<String, Object>> prefetch = List.of(
                Map.of("query", denseVector,
                       "using", "dense",
                       "limit", prefetchLimit),
                Map.of("query", Map.of("indices", sparseVec.indices(), "values", sparseVec.values()),
                       "using", "sparse",
                       "limit", prefetchLimit)
        );

        Map<String, Object> body = Map.of(
                "prefetch", prefetch,
                "query", Map.of("fusion", "rrf"),
                "limit", limit,
                "with_payload", true
        );

        QueryResponse response = qdrantWebClient.post()
                .uri("/collections/{name}/points/query", collectionName)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        resp -> resp.bodyToMono(String.class).map(body2 -> {
                            log.error("Qdrant hybrid search error {}: {}", resp.statusCode(), body2);
                            return new RuntimeException("Qdrant error: " + body2);
                        }))
                .bodyToMono(QueryResponse.class)
                .block();

        if (response == null || response.result() == null || response.result().points() == null) {
            log.warn("Qdrant hybrid search returned null");
            return List.of();
        }
        List<SearchHit> hits = response.result().points().stream().map(this::toSearchHit).toList();
        log.info("Hybrid search (RRF): {} hits (limit={})", hits.size(), limit);
        return hits;
    }

    /**
     * Fetch a specific chunk by docId + chunkIndex for neighbor expansion.
     */
    public List<SearchHit> fetchByDocIdAndChunkIndex(String docId, int chunkIndex) {
        Map<String, Object> filter = Map.of(
                "must", List.of(
                        Map.of("key", "docId",      "match", Map.of("value", docId)),
                        Map.of("key", "chunkIndex", "match", Map.of("value", String.valueOf(chunkIndex)))
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

    /**
     * Delete all points belonging to a document (matched by docId payload field).
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

    // ---- Response DTOs ----

    private record ScrollRequest(
            @JsonProperty("with_payload") boolean withPayload,
            @JsonProperty("with_vector") boolean withVector,
            int limit,
            @JsonProperty("filter") Map<String, Object> filter
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ScrollResponse(ScrollResult result) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ScrollResult(List<ScoredPoint> points) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SearchResponse(List<ScoredPoint> result) {}

    /** Query API (/points/query) wraps results one level deeper: {"result":{"points":[...]}} */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QueryResponse(QueryResult result) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QueryResult(List<ScoredPoint> points) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ScoredPoint(double score, Map<String, Object> payload) {}

    // ---- Helpers ----

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

    private String getString(Map<String, Object> payload, String key) {
        if (payload == null) return null;
        Object v = payload.get(key);
        return v != null ? v.toString() : null;
    }
}
