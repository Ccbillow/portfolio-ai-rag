package com.simon.rag.config;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Duration;

/**
 * Strategy: dev → Ollama llama3.2 (local, free)
 *           prod → Claude claude-3-haiku (Anthropic API)
 */
@Slf4j
@Configuration
public class ChatModelConfig {

    @Bean
    @Profile("dev")
    public ChatLanguageModel ollamaChatModel(
            @Value("${langchain4j.ollama.base-url}") String baseUrl,
            @Value("${langchain4j.ollama.chat-model-name}") String modelName) {

        log.info("ChatLanguageModel → Ollama [{}] at {}", modelName, baseUrl);
        return OllamaChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    @Bean
    @Profile({"local", "prod"})
    public ChatLanguageModel claudeChatModel(
            @Value("${langchain4j.anthropic.api-key}") String apiKey,
            @Value("${langchain4j.anthropic.chat-model-name}") String modelName) {

        log.info("ChatLanguageModel → Claude [{}]", modelName);
        return AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .maxTokens(1024)
                .build();
    }

    @Bean
    @Profile({"local", "prod"})
    public StreamingChatLanguageModel claudeStreamingChatModel(
            @Value("${langchain4j.anthropic.api-key}") String apiKey,
            @Value("${langchain4j.anthropic.chat-model-name}") String modelName) {

        log.info("StreamingChatLanguageModel → Claude [{}]", modelName);
        return AnthropicStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .maxTokens(1024)
                .build();
    }
}
