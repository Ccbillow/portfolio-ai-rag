package com.simon.rag.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.qdrant.QdrantEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Map;

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
    public WebClient qdrantWebClient() {
        WebClient.Builder builder = WebClient.builder()
                .baseUrl("http://" + host + ":" + httpPort);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader("api-key", apiKey);
        }
        WebClient client = builder.build();
        ensureCollectionExists(client);
        return client;
    }

    /** EmbeddingStore for IngestionRunner.addAll() — langchain4j handles writes via gRPC */
    @Bean
    public EmbeddingStore<TextSegment> qdrantEmbeddingStore() {
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

    private void ensureCollectionExists(WebClient client) {
        try {
            client.get()
                    .uri("/collections/{name}", collectionName)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            log.info("Qdrant collection '{}' already exists", collectionName);
        } catch (WebClientResponseException.NotFound e) {
            createCollection(client);
        } catch (Exception e) {
            log.warn("Could not check Qdrant collection, will attempt create: {}", e.getMessage());
            createCollection(client);
        }
    }

    private void createCollection(WebClient client) {
        Map<String, Object> body = Map.of(
                "vectors", Map.of(
                        "size", vectorSize,
                        "distance", "Cosine"
                )
        );
        client.put()
                .uri("/collections/{name}", collectionName)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .block();
        log.info("Created Qdrant collection '{}' (dim={}, distance=Cosine)", collectionName, vectorSize);
    }
}
