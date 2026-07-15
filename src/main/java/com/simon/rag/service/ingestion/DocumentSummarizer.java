package com.simon.rag.service.ingestion;

import com.simon.rag.comm.enums.PromptKey;
import com.simon.rag.config.RagProperties;
import com.simon.rag.llm.LlmClient;
import com.simon.rag.service.prompt.PromptTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * RAPTOR: generates a document-level summary chunk for high-level recall
 * (improves retrieval for broad questions like "tell me about your Alipay experience").
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentSummarizer {

    private final LlmClient llmClient;
    private final PromptTemplateService promptTemplateService;
    private final RagProperties ragProperties;

    /**
     * Returns a document-level summary, or "" when RAPTOR is disabled or generation
     * fails/returns blank (so ingestion continues without a summary chunk).
     */
    public String summarize(String fullText, String fileName, String category) {
        RagProperties.Raptor cfg = ragProperties.getRaptor();
        if (!cfg.isEnabled()) return "";

        String docContext = fullText.length() > cfg.getMaxDocChars()
                ? fullText.substring(0, cfg.getMaxDocChars()) : fullText;
        log.info("RAPTOR: generating document summary for '{}'", fileName);

        String prompt = promptTemplateService.get(PromptKey.RAPTOR_DOCUMENT_SUMMARY)
                .replace("{{fileName}}", fileName)
                .replace("{{category}}", category)
                .replace("{{docText}}", docContext);
        try {
            // One summary per document — no extra gap beyond the shared global timeline.
            String summary = llmClient.generate(prompt, 0);
            if (!summary.isBlank()) {
                log.info("RAPTOR: summary generated ({} chars)", summary.length());
            }
            return summary;
        } catch (Exception e) {
            log.warn("RAPTOR summary generation failed: {}", e.getMessage());
            return "";
        }
    }
}
