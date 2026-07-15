package com.simon.rag.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Single gateway for all non-streaming Claude calls.
 *
 * <p>Owns the cross-cutting call protocol so call sites keep only their business
 * fallback semantics: global throttling ({@link LlmThrottle}), markdown-fence
 * stripping, JSON parsing, and quota-error classification.
 *
 * <p>Streaming stays with the caller (SSE handling is a chat concern) but must
 * share the same throttle via {@link #throttleInteractive()}.
 */
@Component
@RequiredArgsConstructor
public class LlmClient {

    private final ChatLanguageModel chatLanguageModel;
    private final LlmThrottle throttle;
    private final ObjectMapper objectMapper;

    /** Min gap for interactive (chat/eval) answer calls. */
    @Value("${claude.rate-limit-ms:3000}")
    private long interactiveGapMs;

    /** Upper bound on interactive wait before fast-failing with ClaudeBusyException. */
    @Value("${claude.rate-limit-max-wait-ms:15000}")
    private long maxWaitMs;

    public record LlmReply(String text, TokenUsage tokenUsage) {}

    /**
     * Interactive answer call: bounded throttle (fast-fails with ClaudeBusyException
     * under saturation). Returns text plus token usage for caller-side logging.
     */
    public LlmReply chat(String userPrompt) {
        throttle.acquireBounded(interactiveGapMs, maxWaitMs);
        Response<AiMessage> response =
                chatLanguageModel.generate(List.of(UserMessage.from(userPrompt)));
        return new LlmReply(response.content().text(), response.tokenUsage());
    }

    /** Acquires an interactive throttle slot for a streaming call made by the caller. */
    public void throttleInteractive() {
        throttle.acquireBounded(interactiveGapMs, maxWaitMs);
    }

    /**
     * Background call (ingestion, expansion): blocks until the caller's {@code gapMs}
     * has elapsed on the global timeline. Pass 0 for hot-path calls that must not wait.
     */
    public String generate(String userPrompt, long gapMs) {
        return generate(null, userPrompt, gapMs);
    }

    public String generate(String systemPrompt, String userPrompt, long gapMs) {
        throttle.acquire(gapMs);
        List<ChatMessage> messages = new ArrayList<>(2);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(SystemMessage.from(systemPrompt));
        }
        messages.add(UserMessage.from(userPrompt));
        return chatLanguageModel.generate(messages).content().text().strip();
    }

    /**
     * Background call expecting a JSON payload: strips markdown fences and parses.
     * Throws on malformed output — the caller decides its own fallback.
     */
    public <T> T generateJson(String systemPrompt, String userPrompt, long gapMs,
                              TypeReference<T> type) throws Exception {
        String raw = stripMarkdownFences(generate(systemPrompt, userPrompt, gapMs));
        return objectMapper.readValue(raw, type);
    }

    /** True for quota/overload errors that warrant a "try again in a moment" reply. */
    public boolean isQuotaError(Throwable e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("quota") || lower.contains("credit")
                || lower.contains("overload") || lower.contains("529")
                || (lower.contains("rate") && lower.contains("limit"));
    }

    /** Strips markdown code fences that some Claude models insist on wrapping JSON in. */
    private String stripMarkdownFences(String raw) {
        String s = raw.strip();
        if (s.startsWith("```")) {
            int start = s.indexOf('\n');
            if (start < 0) start = 3;
            else start = start + 1;
            int end = s.lastIndexOf("```");
            if (end < 0) end = s.length();
            s = s.substring(start, end).strip();
        }
        return s;
    }
}
