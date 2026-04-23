package com.simon.rag.config;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class ChatModelConfig {

    @Bean
    public ChatLanguageModel claudeChatModel(
            @Value("${langchain4j.anthropic.api-key}") String apiKey,
            @Value("${langchain4j.anthropic.chat-model-name}") String modelName,
            @Value("${langchain4j.anthropic.max-tokens:180}") int maxTokens) {

        log.info("ChatLanguageModel → Claude [{}]", modelName);
        return AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .build();
    }

    @Bean
    public StreamingChatLanguageModel claudeStreamingChatModel(
            @Value("${langchain4j.anthropic.api-key}") String apiKey,
            @Value("${langchain4j.anthropic.chat-model-name}") String modelName,
            @Value("${langchain4j.anthropic.max-tokens:180}") int maxTokens) {

        log.info("StreamingChatLanguageModel → Claude [{}]", modelName);
        return AnthropicStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .build();
    }
}
