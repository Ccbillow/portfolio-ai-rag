package com.simon.rag.service.ingestion;

import com.simon.rag.comm.enums.ChunkType;
import com.simon.rag.comm.enums.PromptKey;
import com.simon.rag.config.RagProperties;
import com.simon.rag.service.impl.PromptTemplateService;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Contextual Retrieval: prepends a Claude-generated context prefix to each regular
 * chunk before embedding, so the dense vector carries company/project/time context
 * that raw chunks often lack. Parallel calls are bounded by a shared semaphore and
 * rate-limited to stay under the Anthropic token/min limit.
 */
@Slf4j
@Component
public class ContextualRetrievalEnricher {

    private final ChatLanguageModel chatLanguageModel;
    private final PromptTemplateService promptTemplateService;
    private final RagProperties ragProperties;
    private final Executor uploadExecutor;

    /** Tracks last Claude API call timestamp for rate limiting. */
    private final AtomicLong lastCrCallTime = new AtomicLong(0);

    /** Shared semaphore bounding concurrency across all documents. */
    private Semaphore crSemaphore;

    public ContextualRetrievalEnricher(ChatLanguageModel chatLanguageModel,
                                       PromptTemplateService promptTemplateService,
                                       RagProperties ragProperties,
                                       @Qualifier("uploadExecutor") Executor uploadExecutor) {
        this.chatLanguageModel = chatLanguageModel;
        this.promptTemplateService = promptTemplateService;
        this.ragProperties = ragProperties;
        this.uploadExecutor = uploadExecutor;
    }

    @PostConstruct
    void initCrSemaphore() {
        this.crSemaphore = new Semaphore(ragProperties.getContextualRetrieval().getConcurrency());
    }

    /**
     * Returns the per-chunk text to embed, aligned to {@code segments} order.
     * When Contextual Retrieval is disabled, returns raw chunk texts.
     * Summary chunks embed as-is (they ARE the context description).
     */
    public List<String> buildEmbeddingTexts(List<TextSegment> segments, String fullText) {
        List<String> embeddingTexts = new ArrayList<>(segments.size());
        RagProperties.ContextualRetrieval crCfg = ragProperties.getContextualRetrieval();
        if (!crCfg.isEnabled()) {
            segments.forEach(seg -> embeddingTexts.add(seg.text()));
            return embeddingTexts;
        }

        String docContext = fullText.length() > crCfg.getMaxDocChars()
                ? fullText.substring(0, crCfg.getMaxDocChars()) : fullText;
        log.info("Contextual Retrieval: generating context prefixes for {} chunks (concurrency={})",
                segments.size(), crCfg.getConcurrency());
        long crStart = System.currentTimeMillis();

        // Acquire semaphore BEFORE submitting to executor so pool threads
        // never block waiting for a permit (same pattern as ChunkMetadataEnricher).
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (TextSegment seg : segments) {
            try {
                crSemaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> {
                try {
                    if (ChunkType.DOCUMENT_SUMMARY.getValue().equals(seg.metadata().getString("chunkType"))) {
                        return seg.text();
                    }
                    throttleCrCall(crCfg.getRateLimitMs());
                    String prefix = generateContextPrefix(docContext, seg.text());
                    return prefix.isBlank() ? seg.text() : prefix + "\n\n" + seg.text();
                } finally {
                    crSemaphore.release();
                }
            }, uploadExecutor);
            futures.add(f);
        }
        futures.forEach(f -> embeddingTexts.add(f.join()));
        log.info("Contextual Retrieval: done in {}ms", System.currentTimeMillis() - crStart);
        return embeddingTexts;
    }

    /**
     * Calls Claude to generate a 1–2 sentence context description for a chunk.
     * Falls back to empty string on any error so ingestion continues uninterrupted.
     */
    private String generateContextPrefix(String fullDocText, String chunkText) {
        String prompt = promptTemplateService.get(PromptKey.CONTEXTUAL_RETRIEVAL_PREFIX)
                .replace("{{docText}}", fullDocText)
                .replace("{{chunkText}}", chunkText);
        try {
            return chatLanguageModel.generate(List.of(UserMessage.from(prompt)))
                    .content().text().strip();
        } catch (Exception e) {
            log.warn("Context prefix generation failed, using raw chunk: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Rate-limits Claude calls to stay under the 50K-token/min Anthropic rate limit.
     * Each call sends ~4K input tokens (maxDocChars), so a 6 s gap keeps us at
     * ~10 calls/min ≈ 40K tokens/min with headroom.
     */
    private void throttleCrCall(long minGapMs) {
        while (true) {
            long prev = lastCrCallTime.get();
            long now = System.currentTimeMillis();
            long next = Math.max(now, prev + minGapMs);
            if (lastCrCallTime.compareAndSet(prev, next)) {
                long waitMs = next - now;
                if (waitMs > 0) {
                    try { Thread.sleep(waitMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                return;
            }
        }
    }
}
