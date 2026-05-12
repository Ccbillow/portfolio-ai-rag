package com.simon.rag.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
public class QdrantConfig {

    @Value("${qdrant.host}")
    private String host;

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
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .doOnConnected(conn -> conn.addHandlerLast(
                        new ReadTimeoutHandler(10, TimeUnit.SECONDS)));

        WebClient.Builder builder = WebClient.builder()
                .baseUrl("http://" + host + ":" + httpPort)
                .clientConnector(new ReactorClientHttpConnector(httpClient));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader("api-key", apiKey);
        }
        WebClient client = builder.build();
        ensureCollectionExists(client);
        return client;
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

    /**
     * Named "dense" vector (1536-dim Cosine) + "sparse" BM25 sparse vector.
     * Hybrid search via Qdrant Query API with RRF fusion requires named vectors.
     */
    private void createCollection(WebClient client) {
        Map<String, Object> body = Map.of(
                "vectors", Map.of(
                        "dense", Map.of("size", vectorSize, "distance", "Cosine")
                ),
                "sparse_vectors", Map.of(
                        "sparse", Map.of()
                )
        );
        try {
            client.put()
                    .uri("/collections/{name}", collectionName)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            log.info("Created Qdrant collection '{}' (dense={}d Cosine + sparse BM25)", collectionName, vectorSize);
        } catch (Exception e) {
            log.error("Failed to create Qdrant collection '{}': {}", collectionName, e.getMessage());
            throw new IllegalStateException("Qdrant collection creation failed — check Qdrant is running", e);
        }
    }
}
