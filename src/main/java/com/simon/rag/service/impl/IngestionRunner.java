package com.simon.rag.service.impl;

import com.simon.rag.comm.enums.BehavioralTag;
import com.simon.rag.comm.enums.ChunkType;
import com.simon.rag.comm.enums.IngestionStatus;
import com.simon.rag.comm.enums.IntentTag;
import com.simon.rag.comm.enums.PromptKey;
import com.simon.rag.config.RagProperties;
import com.simon.rag.dao.DocumentMapper;
import com.simon.rag.service.impl.SparseVectorizer.SparseVector;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Async document ingestion: parse → chunk → embed (dense + sparse) → upsert to Qdrant.
 *
 * Uses QdrantSearchService.upsertPoints() directly (not LangChain4j EmbeddingStore)
 * so we can store both named dense vectors and BM25 sparse vectors in the same point.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionRunner {

    private final EmbeddingModel embeddingModel;
    private final ChatLanguageModel chatLanguageModel;
    private final QdrantSearchService qdrantSearchService;
    private final SparseVectorizer sparseVectorizer;
    private final DocumentMapper documentMapper;
    private final RedisCacheService redisCacheService;
    private final RagProperties ragProperties;
    private final PromptTemplateService promptTemplateService;
    private final MarkdownHeadingChunkSplitter markdownSplitter;
    private final ChunkMetadataEnricher metadataEnricher;

    private final Tika tika = new Tika();

    // Field-injected with qualifier; non-final to avoid Lombok constructor conflict with @Qualifier
    @Autowired
    @Qualifier("uploadExecutor")
    private Executor uploadExecutor;

    /** Tracks last Claude API call timestamp for contextual retrieval rate limiting. */
    private final AtomicLong lastCrCallTime = new AtomicLong(0);

    @Async("taskExecutor")
    public void ingest(Long docId, String taskId, String fileName, String category) {
        redisCacheService.setIngestionStatus(taskId, IngestionStatus.PROCESSING.name());
        Path filePath = Path.of(ragProperties.getUpload().getDir(), taskId);

        try {
            // 1 & 2. Stream-parse to plain text — avoids loading the full file into heap
            String text;
            try (InputStream fileIn = Files.newInputStream(filePath)) {
                text = normalizeText(tika.parseToString(fileIn));
            }
            log.info("Tika parsed {} chars from '{}'", text.length(), fileName);

            // 3. Split: markdown heading splitter for .md files, semantic paragraph split for others
            RagProperties.Embedding cfg = ragProperties.getEmbedding();
            List<TextSegment> segments;

            if (markdownSplitter.isMarkdown(fileName, text)) {
                log.info("Markdown heading splitter for '{}'", fileName);
                List<TextSegment> mdSegments = markdownSplitter.split(text);
                segments = new ArrayList<>();
                for (int i = 0; i < mdSegments.size(); i++) {
                    TextSegment seg = mdSegments.get(i);
                    seg.metadata()
                        .put("docId", String.valueOf(docId))
                        .put("fileName", fileName)
                        .put("category", category)
                        .put("chunkIndex", String.valueOf(i))
                        .put("chunkTotal", String.valueOf(mdSegments.size()));
                    segments.add(seg);
                }
                log.info("Markdown split: {} chunks from '{}'", segments.size(), fileName);
            } else {
                List<String> rawChunks = semanticSplit(text, cfg.getChunkSize(), cfg.getChunkOverlap());
                log.info("Semantic split: {} chunks from '{}' ({} chars)", rawChunks.size(), fileName, text.length());
                segments = new ArrayList<>();
                for (int i = 0; i < rawChunks.size(); i++) {
                    Metadata meta = new Metadata();
                    meta.put("docId", String.valueOf(docId));
                    meta.put("fileName", fileName);
                    meta.put("category", category);
                    meta.put("chunkIndex", String.valueOf(i));
                    meta.put("chunkTotal", String.valueOf(rawChunks.size()));
                    segments.add(TextSegment.from(rawChunks.get(i), meta));
                }
            }

            // 4. Metadata enrichment — runs in parallel (Semaphore(3)), before RAPTOR
            List<ChunkMetadataEnricher.EnrichmentResult> enrichments = metadataEnricher.enrichAll(segments);
            log.info("Metadata enrichment done. chunks={} fileName={}", segments.size(), fileName);

            // 5. RAPTOR: prepend a document-level summary chunk for high-level recall.
            //     Improves retrieval for broad questions ("tell me about your Alipay experience").
            RagProperties.Raptor raptorCfg = ragProperties.getRaptor();
            if (raptorCfg.isEnabled()) {
                String docContext = text.length() > raptorCfg.getMaxDocChars()
                        ? text.substring(0, raptorCfg.getMaxDocChars()) : text;
                log.info("RAPTOR: generating document summary for '{}'", fileName);
                String summary = generateDocumentSummary(docContext, fileName, category);
                if (!summary.isBlank()) {
                    Metadata summaryMeta = new Metadata();
                    summaryMeta.put("docId", String.valueOf(docId));
                    summaryMeta.put("fileName", fileName);
                    summaryMeta.put("category", category);
                    summaryMeta.put("chunkIndex", "summary");
                    summaryMeta.put("chunkTotal", String.valueOf(segments.size()));
                    summaryMeta.put("chunkType", ChunkType.DOCUMENT_SUMMARY.getValue());
                    segments.add(0, TextSegment.from(summary, summaryMeta));
                    log.info("RAPTOR: summary generated ({} chars)", summary.length());
                }
            }

            // 5. Contextual Retrieval: prepend a Claude-generated context prefix to each regular chunk
            //    before embedding. Parallel calls bounded by a semaphore to avoid API overload.
            //    Summary chunks embed as-is (they ARE the context description).
            List<String> embeddingTexts = new ArrayList<>(segments.size());
            RagProperties.ContextualRetrieval crCfg = ragProperties.getContextualRetrieval();
            if (crCfg.isEnabled()) {
                String docContext = text.length() > crCfg.getMaxDocChars()
                        ? text.substring(0, crCfg.getMaxDocChars()) : text;
                log.info("Contextual Retrieval: generating context prefixes for {} chunks (concurrency={})",
                        segments.size(), crCfg.getConcurrency());
                long crStart = System.currentTimeMillis();
                Semaphore semaphore = new Semaphore(crCfg.getConcurrency());
                List<CompletableFuture<String>> futures = segments.stream()
                        .map(seg -> CompletableFuture.supplyAsync(() -> {
                            if ("document_summary".equals(seg.metadata().getString("chunkType"))) {
                                return seg.text();
                            }
                            try {
                                semaphore.acquire();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return seg.text();
                            }
                            try {
                                throttleCrCall(crCfg.getRateLimitMs());
                                String prefix = generateContextPrefix(docContext, seg.text());
                                return prefix.isBlank() ? seg.text() : prefix + "\n\n" + seg.text();
                            } finally {
                                semaphore.release();
                            }
                        }, uploadExecutor))
                        .toList();
                futures.forEach(f -> embeddingTexts.add(f.join()));
                log.info("Contextual Retrieval: done in {}ms", System.currentTimeMillis() - crStart);
            } else {
                segments.forEach(seg -> embeddingTexts.add(seg.text()));
            }

            // 6. Compute dense embeddings (OpenAI) — batch to stay within per-request limits
            List<Embedding> embeddings = new ArrayList<>();
            int batchSize = 100;
            for (int start = 0; start < embeddingTexts.size(); start += batchSize) {
                List<TextSegment> batch = embeddingTexts
                        .subList(start, Math.min(start + batchSize, embeddingTexts.size()))
                        .stream().map(TextSegment::from).toList();
                embeddings.addAll(embeddingModel.embedAll(batch).content());
            }

            // 7. Build Qdrant points with dense + sparse vectors
            List<QdrantSearchService.PointData> points = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                TextSegment seg = segments.get(i);
                float[] dense = embeddings.get(i).vector();
                SparseVector sparse = sparseVectorizer.vectorize(seg.text());

                Map<String, Object> vectors = new LinkedHashMap<>();
                vectors.put("dense", dense);
                if (!sparse.isEmpty()) {
                    vectors.put("sparse",
                            Map.of("indices", sparse.indices(), "values", sparse.values()));
                }

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("text_segment", seg.text());
                Metadata meta = seg.metadata();
                payload.put("docId",       meta.getString("docId"));
                payload.put("fileName",    meta.getString("fileName"));
                payload.put("category",    meta.getString("category"));
                payload.put("chunkIndex",  meta.getString("chunkIndex"));
                payload.put("chunkTotal",  meta.getString("chunkTotal"));

                // Phase 12: merge companies from heading metadata + filename + chunk text
                List<String> companies = buildCompanies(fileName, seg);
                if (!companies.isEmpty()) payload.put("companies", companies);

                // Phase 12: enrichment metadata
                boolean isSummary = "summary".equals(meta.getString("chunkIndex"));
                ChunkMetadataEnricher.EnrichmentResult enr;
                if (isSummary) {
                    enr = ChunkMetadataEnricher.EnrichmentResult.empty();
                    payload.put("chunk_type", ChunkType.DOCUMENT_SUMMARY.getValue());
                    payload.put("chunk_id", docId + "_summary");
                    payload.put("chunk_index", -1);
                } else {
                    int enrIdx = Integer.parseInt(meta.getString("chunkIndex"));
                    enr = enrichments.get(enrIdx);
                    payload.put("chunk_type", enr.chunkType() != null
                            ? enr.chunkType().getValue() : "");
                    payload.put("chunk_id", docId + "_chunk_" + i);
                    payload.put("chunk_index", i);
                }
                payload.put("intent_tags", enr.intentTags().stream()
                        .map(IntentTag::getValue).toList());
                payload.put("behavioral_tags", enr.behavioralTags().stream()
                        .map(BehavioralTag::getValue).toList());
                payload.put("project", enr.project());
                payload.put("skills", enr.skills());
                payload.put("parent_id", docId);
                payload.put("chunk_total", segments.size());

                points.add(new QdrantSearchService.PointData(
                        UUID.randomUUID().toString(), vectors, payload));
            }

            // 8. Upsert to Qdrant
            qdrantSearchService.upsertPoints(points);

            // 9. Mark done
            redisCacheService.setIngestionStatus(taskId, IngestionStatus.COMPLETED.name());
            documentMapper.updateIngestionResult(
                    docId, IngestionStatus.COMPLETED.name(), segments.size(), null);
            log.info("Ingestion completed: docId={}, chunks={}", docId, segments.size());

            // 10. Update IDF corpus with all chunk texts (including summary if present)
            sparseVectorizer.addChunksToCorpus(segments.stream().map(TextSegment::text).toList());

        } catch (Exception e) {
            log.error("Ingestion failed for docId={}", docId, e);
            redisCacheService.setIngestionStatus(taskId, IngestionStatus.FAILED.name());
            documentMapper.updateIngestionResult(
                    docId, IngestionStatus.FAILED.name(), 0, sanitizeErrorMessage(e));
        } finally {
            try { Files.deleteIfExists(filePath); }
            catch (Exception ex) { log.warn("Failed to delete temp file {}: {}", filePath, ex.getMessage()); }
        }
    }

    /**
     * Semantic chunking: splits text on paragraph boundaries (\n\n), merges short consecutive
     * paragraphs, and falls back to sentence-level splitting for oversized paragraphs.
     * Prevents mid-sentence cuts produced by fixed character splitting.
     */
    private List<String> semanticSplit(String text, int targetSize, int overlap) {
        List<String> paragraphs = Arrays.stream(text.split("\n\n"))
                .map(String::strip)
                .filter(s -> !s.isBlank())
                .toList();

        List<String> chunks = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        for (String para : paragraphs) {
            if (para.length() > targetSize) {
                if (!buf.isEmpty()) { chunks.add(buf.toString()); buf.setLength(0); }
                splitBySentence(para, targetSize, overlap, chunks);
                continue;
            }
            if (!buf.isEmpty() && buf.length() + 2 + para.length() > targetSize) {
                chunks.add(buf.toString());
                String overlapText = trailingOverlap(buf.toString(), overlap);
                buf.setLength(0);
                if (!overlapText.isBlank()) buf.append(overlapText).append("\n\n");
            }
            if (!buf.isEmpty()) buf.append("\n\n");
            buf.append(para);
        }
        if (!buf.isEmpty()) chunks.add(buf.toString());

        return chunks.isEmpty() ? List.of(text) : chunks;
    }

    private void splitBySentence(String text, int targetSize, int overlap, List<String> out) {
        String[] sentences = text.split("(?<=[.!?])\\s+");
        StringBuilder buf = new StringBuilder();
        for (String sentence : sentences) {
            if (!buf.isEmpty() && buf.length() + 1 + sentence.length() > targetSize) {
                out.add(buf.toString());
                String overlapText = trailingOverlap(buf.toString(), overlap);
                buf.setLength(0);
                if (!overlapText.isBlank()) buf.append(overlapText).append(" ");
            }
            if (!buf.isEmpty()) buf.append(" ");
            buf.append(sentence);
        }
        if (!buf.isEmpty()) out.add(buf.toString());
    }

    /** Returns the trailing sentence fragment of `chunk` up to `overlap` chars for context continuity. */
    private String trailingOverlap(String chunk, int overlap) {
        if (overlap <= 0 || chunk.length() <= overlap) return "";
        String tail = chunk.substring(chunk.length() - overlap);
        int sentenceStart = tail.indexOf(". ");
        return sentenceStart >= 0 ? tail.substring(sentenceStart + 2) : tail;
    }

    /** RAPTOR: calls Claude to generate a document-level summary chunk (prompt from DB). */
    private String generateDocumentSummary(String fullDocText, String fileName, String category) {
        String prompt = promptTemplateService.get(PromptKey.RAPTOR_DOCUMENT_SUMMARY)
                .replace("{{fileName}}", fileName)
                .replace("{{category}}", category)
                .replace("{{docText}}", fullDocText);
        try {
            return chatLanguageModel.generate(List.of(UserMessage.from(prompt)))
                    .content().text().strip();
        } catch (Exception e) {
            log.warn("RAPTOR summary generation failed: {}", e.getMessage());
            return "";
        }
    }

    /** Returns all configured company names found in the given text (case-insensitive). */
    private List<String> extractCompaniesFromText(String text) {
        String lower = text.toLowerCase();
        return ragProperties.getCompanies().stream()
                .filter(c -> lower.contains(c.toLowerCase()))
                .toList();
    }

    /**
     * Reverse-derives parent companies from company-subgroups config.
     * If a chunk is tagged [OCBC], it also gets [Deloitte] so parent-company
     * searches can find sub-project chunks without sub-project query expansion.
     */
    private void addParentCompanies(List<String> companies) {
        ragProperties.getCompanySubgroups().forEach((parent, subs) -> {
            for (String sub : subs) {
                if (companies.contains(sub) && !companies.contains(parent)) {
                    companies.add(parent);
                }
            }
        });
    }

    /**
     * Phase 12: merge companies from heading metadata + chunk text + filename.
     * If the markdown heading contains a company name, it takes priority as the
     * most reliable signal for this specific chunk.
     */
    private List<String> buildCompanies(String fileName, TextSegment segment) {
        Set<String> companies = new LinkedHashSet<>();

        String heading = segment.metadata().getString("heading");
        if (heading != null && !heading.isBlank()) {
            companies.addAll(extractCompaniesFromText(heading));
        }

        if (companies.isEmpty()) {
            companies.addAll(extractCompaniesFromText(fileName));
        }

        companies.addAll(extractCompaniesFromText(segment.text()));

        List<String> result = new ArrayList<>(companies);
        addParentCompanies(result);
        return result;
    }

    /**
     * Calls Claude to generate a 1–2 sentence context description for a chunk.
     * The description is prepended to the chunk text before dense embedding so the
     * vector carries company/project/time context that raw chunks often lack.
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
     * Rate-limits contextual retrieval Claude calls to stay under the 50K-token/min
     * Anthropic rate limit. Each call sends ~4K input tokens (maxDocChars), so a
     * 6 s gap keeps us at ~10 calls/min ≈ 40K tokens/min with headroom.
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

    /**
     * Normalizes Tika output: converts single newlines to spaces (prevents word
     * concatenation at paragraph boundaries in DOCX/PDF), preserves double newlines.
     */
    private String normalizeText(String text) {
        return text
                .replace("\r\n", "\n")
                .replaceAll("(?<!\n)\n(?!\n)", " ")
                .replaceAll("\n{3,}", "\n\n");
    }

    /** Truncates and strips control chars from exception messages before storing in DB. */
    private String sanitizeErrorMessage(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return "Unknown error";
        String cleaned = msg.replaceAll("[\\p{Cntrl}&&[^\t\n]]", " ");
        return cleaned.length() > 500 ? cleaned.substring(0, 500) : cleaned;
    }
}
