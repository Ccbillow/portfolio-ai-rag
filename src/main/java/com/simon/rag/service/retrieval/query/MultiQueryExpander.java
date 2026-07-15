package com.simon.rag.service.retrieval.query;

import com.simon.rag.llm.LlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class MultiQueryExpander {

    private final LlmClient llmClient;

    @Value("${rag.multi-query.enabled:false}")
    private boolean enabled;

    private static final String PROMPT = """
            Rephrase the following interview question into 2 alternative search queries \
            that would retrieve the same information from a vector database.
            Output exactly 2 lines, one query per line. No numbering, no explanation.

            Question: %s
            """;

    /**
     * Returns [originalQuestion, variant1, variant2].
     * Falls back to [originalQuestion] on any error so retrieval always proceeds.
     */
    public List<String> expand(String question) {
        if (!enabled) return List.of(question);
        try {
            // Hot path (chat latency): gap 0 — no extra wait beyond the shared timeline.
            String response = llmClient.generate(String.format(PROMPT, question), 0);

            List<String> variants = Arrays.stream(response.split("\n"))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .map(s -> s.replaceFirst("^\\d+[.)\\-]\\s*", ""))
                    .limit(2)
                    .collect(Collectors.toList());

            List<String> all = new ArrayList<>();
            all.add(question);
            all.addAll(variants);
            log.info("MultiQuery: {} → {} queries", question, all.size());
            return all;
        } catch (Exception e) {
            log.warn("MultiQuery expansion failed, using original only: {}", e.getMessage());
            return List.of(question);
        }
    }
}
