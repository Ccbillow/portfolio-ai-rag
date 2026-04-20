package com.simon.rag.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Slf4j
@Configuration
public class QdrantConfig {

    @Value("${qdrant.host}")
    private String host;

    @Value("${qdrant.port:6334}")
    private int grpcPort;

    @Value("${qdrant.http-port:6333}")
    private int httpPort;

    @Value("${qdrant.collection-name}")
    private String collectionName;

    @Value("${qdrant.vector-size:1536}")
    private int vectorSize;

    @Value("${qdrant.api-key:}")
    private String apiKey;

    @Bean
    public EmbeddingStore<TextSegment> qdrantEmbeddingStore() {
        ensureCollectionExists();

        QdrantEmbeddingStore.Builder builder = QdrantEmbeddingStore.builder()
                .host(host)
                .port(grpcPort)
                .collectionName(collectionName);

        if (apiKey != null && !apiKey.isBlank()) {
            builder.apiKey(apiKey);
        }

        log.info("EmbeddingStore → Qdrant [{}] grpc://{}:{}", collectionName, host, grpcPort);
        return builder.build();
    }

    private void ensureCollectionExists() {
        HttpClient client = HttpClient.newHttpClient();
        String baseUrl = "http://" + host + ":" + httpPort + "/collections/" + collectionName;

        try {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder().uri(URI.create(baseUrl)).GET();
            if (apiKey != null && !apiKey.isBlank()) {
                reqBuilder.header("api-key", apiKey);
            }

            HttpResponse<String> resp = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200) {
                log.info("Qdrant collection '{}' already exists", collectionName);
                return;
            }

            // Create collection — vector size must match the embedding model output
            String body = String.format(
                    "{\"vectors\": {\"size\": %d, \"distance\": \"Cosine\"}}", vectorSize);

            HttpRequest.Builder createBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body));
            if (apiKey != null && !apiKey.isBlank()) {
                createBuilder.header("api-key", apiKey);
            }

            HttpResponse<String> createResp = client.send(createBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());
            log.info("Created Qdrant collection '{}', status={}", collectionName, createResp.statusCode());

        } catch (Exception e) {
            log.warn("Could not verify/create Qdrant collection '{}': {}", collectionName, e.getMessage());
        }
    }
}
