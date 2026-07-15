package com.simon.rag.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class CohereConfig {

    @Bean
    public WebClient cohereWebClient(@Value("${cohere.api-key:}") String apiKey) {
        return WebClient.builder()
                .baseUrl("https://api.cohere.com")
                .clientConnector(WebClients.connector(5_000, 15))
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
