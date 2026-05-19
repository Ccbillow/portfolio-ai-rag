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

    public record SearchHit(double score, String text, String fileName, String category, String docId, int chunkIndex, List<String> companies) {}

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
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        resp -> resp.bodyToMono(String.class).map(errBody -> {
                            log.error("Qdrant upsertPoints error {}: {}", resp.statusCode(), errBody);
                            return new RuntimeException("Qdrant upsert failed [" + resp.statusCode() + "]");
                        }))
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
     * Pass company (e.g. "Alipay") to restrict results to that company's documents.
     */
    public List<SearchHit> search(float[] queryVector, int topK, double minScore, String company) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("vector", Map.of("name", "dense", "vector", queryVector));
        body.put("limit", topK);
        body.put("with_payload", true);
        body.put("with_vector", false);
        body.put("score_threshold", minScore);
        if (company != null && !company.isBlank()) {
            body.put("filter", companyFilter(company));
        }

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
        log.info("Dense search: {} hits (topK={}, minScore={}, company={})", hits.size(), topK, minScore, company);
        return hits;
    }

    public List<SearchHit> search(float[] queryVector, int topK, double minScore) {
        return search(queryVector, topK, minScore, null);
    }

    /**
     * Hybrid search: dense + BM25 sparse, fused with RRF via Qdrant Query API.
     * Returns RRF-ranked results — do NOT apply cosine min-score threshold here.
     * Pass company to restrict to that company's documents.
     */
    public List<SearchHit> hybridSearch(float[] denseVector, SparseVector sparseVec, int limit, String company) {
        int prefetchLimit = limit * 2;

        Map<String, Object> densePrefetch = new java.util.LinkedHashMap<>();
        densePrefetch.put("query", denseVector);
        densePrefetch.put("using", "dense");
        densePrefetch.put("limit", prefetchLimit);

        Map<String, Object> sparsePrefetch = new java.util.LinkedHashMap<>();
        sparsePrefetch.put("query", Map.of("indices", sparseVec.indices(), "values", sparseVec.values()));
        sparsePrefetch.put("using", "sparse");
        sparsePrefetch.put("limit", prefetchLimit);

        if (company != null && !company.isBlank()) {
            Map<String, Object> filter = companyFilter(company);
            densePrefetch.put("filter", filter);
            sparsePrefetch.put("filter", filter);
        }

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("prefetch", List.of(densePrefetch, sparsePrefetch));
        body.put("query", Map.of("fusion", "rrf"));
        body.put("limit", limit);
        body.put("with_payload", true);

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
        log.info("Hybrid search (RRF): {} hits (limit={}, company={})", hits.size(), limit, company);
        return hits;
    }

    public List<SearchHit> hybridSearch(float[] denseVector, SparseVector sparseVec, int limit) {
        return hybridSearch(denseVector, sparseVec, limit, null);
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
                .onStatus(status -> status.is4xxClientError() || status.is5xxServerError(),
                        resp -> resp.bodyToMono(String.class).map(errBody -> {
                            log.error("Qdrant deleteByDocId error {}: {}", resp.statusCode(), errBody);
                            return new RuntimeException("Qdrant delete failed [" + resp.statusCode() + "]");
                        }))
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
                idx,
                getStringList(p, "companies")
        );
    }

    private String getString(Map<String, Object> payload, String key) {
        if (payload == null) return null;
        Object v = payload.get(key);
        return v != null ? v.toString() : null;
    }

    @SuppressWarnings("unchecked")
    private List<String> getStringList(Map<String, Object> payload, String key) {
        if (payload == null) return List.of();
        Object v = payload.get(key);
        if (v instanceof List<?>) return (List<String>) v;
        return List.of();
    }

    /**
     * Qdrant filter: chunks where companies[] contains the target company, or is empty.
     * Sibling exclusion is NOT applied here — it is handled post-retrieval in
     * ChatServiceImpl.retrieve() using an allowlist, which correctly handles
     * mixed-label chunks (e.g. ["Deloitte","OCBC"]) without over-filtering.
     */
    private Map<String, Object> companyFilter(String company) {
        Map<String, Object> filter = new java.util.LinkedHashMap<>();
        filter.put("should", List.of(
                Map.of("key", "companies", "match", Map.of("any", List.of(company))),
                Map.of("is_empty", Map.of("key", "companies"))
        ));
        return filter;
    }
}
