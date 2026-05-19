package com.simon.rag.config;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.concurrent.Executor;

@Slf4j
@Configuration
public class ChatModelConfig {

    @Bean
    public ChatLanguageModel claudeChatModel(
            @Value("${langchain4j.anthropic.api-key}") String apiKey,
            @Value("${langchain4j.anthropic.chat-model-name}") String modelName,
            @Value("${langchain4j.anthropic.max-tokens:500}") int maxTokens) {

        log.info("ChatLanguageModel → Claude [{}]", modelName);
        return AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    /**
     * Dedicated executor for parallel contextual retrieval Claude calls in IngestionRunner.
     * Kept separate from the main taskExecutor to prevent parent thread deadlock:
     * the @Async ingest() thread blocks on Future.join() while children run on this pool.
     */
    @Bean("uploadExecutor")
    public Executor uploadExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("cr-task-");
        executor.initialize();
        return executor;
    }

    @Bean
    public StreamingChatLanguageModel claudeStreamingChatModel(
            @Value("${langchain4j.anthropic.api-key}") String apiKey,
            @Value("${langchain4j.anthropic.chat-model-name}") String modelName,
            @Value("${langchain4j.anthropic.max-tokens:500}") int maxTokens) {

        log.info("StreamingChatLanguageModel → Claude [{}]", modelName);
        return AnthropicStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .timeout(Duration.ofSeconds(60))
                .build();
    }
}
