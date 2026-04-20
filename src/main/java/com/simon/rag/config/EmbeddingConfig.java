package com.simon.rag.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Duration;

/**
 * Strategy: dev → Ollama nomic-embed-text (local, free)
 *           prod → OpenAI text-embedding-3-small
 *
 * Anthropic/Claude has no public embedding API, so OpenAI handles
 * vectorisation in prod while Claude handles chat generation.
 */
@Slf4j
@Configuration
public class EmbeddingConfig {

    @Bean
    @Profile("dev")
    public EmbeddingModel ollamaEmbeddingModel(
            @Value("${langchain4j.ollama.base-url}") String baseUrl,
            @Value("${langchain4j.ollama.embedding-model-name}") String modelName) {

        log.info("EmbeddingModel → Ollama [{}] at {}", modelName, baseUrl);
        return OllamaEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    @Bean
    @Profile({"local", "prod"})
    public EmbeddingModel openAiEmbeddingModel(
            @Value("${langchain4j.open-ai.api-key}") String apiKey,
            @Value("${langchain4j.open-ai.embedding-model-name}") String modelName) {

        log.info("EmbeddingModel → OpenAI [{}]", modelName);
        return OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(30))
                .build();
    }
}
