package com.simon.rag.service.impl;

import com.simon.rag.comm.constant.CacheConstant;
import com.simon.rag.comm.enums.IngestionStatus;
import com.simon.rag.config.RagProperties;
import com.simon.rag.dao.DocumentMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentByCharacterSplitter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Separate @Service so that @Async proxying works correctly.
 * DocumentServiceImpl calls this bean — Spring routes through the proxy,
 * and the method executes on a background thread from the task executor pool.
 *
 * Design note — why save to disk first?
 *   The HTTP request (and MultipartFile bytes) ends before the async thread runs.
 *   Persisting to disk decouples the file lifetime from the HTTP lifecycle,
 *   and also enables restart-recovery: a scanner can find PENDING records in MySQL
 *   and re-trigger ingestion by reading from uploadDir/{taskId}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionRunner {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final DocumentMapper documentMapper;
    private final StringRedisTemplate redisTemplate;
    private final RagProperties ragProperties;

    // Singletons — heavyweight objects, created once on startup
    private final Tika tika = new Tika();
    private DocumentByCharacterSplitter splitter;

    @PostConstruct
    void initSplitter() {
        RagProperties.Embedding cfg = ragProperties.getEmbedding();
        splitter = new DocumentByCharacterSplitter(cfg.getChunkSize(), cfg.getChunkOverlap());
        log.info("DocumentSplitter initialized: chunkSize={}, chunkOverlap={}",
                cfg.getChunkSize(), cfg.getChunkOverlap());
    }

    /**
     * @param docId    MySQL row id for status updates
     * @param taskId   also the filename under uploadDir — used for restart recovery
     * @param fileName original filename (for Qdrant payload / logging)
     * @param category knowledge category stored in Qdrant payload
     */
    @Async
    public void ingest(Long docId, String taskId, String fileName, String category) {
        redisTemplate.opsForValue().set(
                CacheConstant.INGEST_TASK_PREFIX + taskId, IngestionStatus.PROCESSING.name());
        Path filePath = Path.of(ragProperties.getUpload().getDir(), taskId);

        try {
            // 1. Read file from disk (saved by upload() before this thread started)
            byte[] fileBytes = Files.readAllBytes(filePath);

            // 2. Parse to plain text (auto-detects PDF / DOCX / TXT / HTML)
            String text = tika.parseToString(new ByteArrayInputStream(fileBytes));
            log.info("Tika parsed {} chars from '{}'", text.length(), fileName);

            // 3. Split into overlapping chunks
            List<TextSegment> rawSegments =
                    splitter.split(dev.langchain4j.data.document.Document.from(text));

            // 4. Attach retrieval metadata so we can show sources in chat responses
            List<TextSegment> segments = rawSegments.stream()
                    .map(seg -> {
                        Metadata meta = new Metadata();
                        meta.put("docId", String.valueOf(docId));
                        meta.put("fileName", fileName);
                        meta.put("category", category);
                        return TextSegment.from(seg.text(), meta);
                    })
                    .collect(Collectors.toList());

            // 5. 💰 Call OpenAI — cost = tokens × $0.02/1M
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

            // 6. Store vectors + payloads in Qdrant
            embeddingStore.addAll(embeddings, segments);

            // 7. Mark done
            redisTemplate.opsForValue().set(
                    CacheConstant.INGEST_TASK_PREFIX + taskId, IngestionStatus.COMPLETED.name());
            documentMapper.updateIngestionResult(
                    docId, IngestionStatus.COMPLETED.name(), segments.size(), null);
            log.info("Ingestion completed: docId={}, chunks={}", docId, segments.size());

            // 8. Clean up temp file — no longer needed after vectors are in Qdrant
            Files.deleteIfExists(filePath);

        } catch (Exception e) {
            log.error("Ingestion failed for docId={}", docId, e);
            redisTemplate.opsForValue().set(
                    CacheConstant.INGEST_TASK_PREFIX + taskId, IngestionStatus.FAILED.name());
            documentMapper.updateIngestionResult(
                    docId, IngestionStatus.FAILED.name(), 0, e.getMessage());
            // Keep temp file on disk so a retry job can re-process without re-upload
        }
    }
}
