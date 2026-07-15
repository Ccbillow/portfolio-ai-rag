package com.simon.rag.service.ingestion;

import com.simon.rag.service.cache.RedisCacheService;
import com.simon.rag.service.vectorstore.EmbeddingService;
import com.simon.rag.service.vectorstore.QdrantSearchService;
import com.simon.rag.service.vectorstore.SparseVectorizer;

import com.simon.rag.comm.enums.ChunkType;
import com.simon.rag.comm.enums.IngestionStatus;
import com.simon.rag.dao.DocumentMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Async document ingestion orchestrator: parse → chunk → enrich → summarize →
 * contextualize → embed (dense + sparse) → upsert to Qdrant.
 *
 * <p>Each stage is delegated to a dedicated component in {@code com.simon.rag.service.ingestion};
 * this class only wires the pipeline and coordinates status/persistence.
 *
 * <p>Uses QdrantSearchService.upsertPoints() directly (not LangChain4j EmbeddingStore)
 * so we can store both named dense vectors and BM25 sparse vectors in the same point.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionRunner {

    private final EmbeddingService embeddingService;
    private final QdrantSearchService qdrantSearchService;
    private final SparseVectorizer sparseVectorizer;
    private final DocumentMapper documentMapper;
    private final RedisCacheService redisCacheService;
    private final UploadStorage uploadStorage;
    private final ChunkMetadataEnricher metadataEnricher;
    private final TextExtractor textExtractor;
    private final ChunkSplitterSelector chunkSplitterSelector;
    private final DocumentSummarizer documentSummarizer;
    private final ContextualRetrievalEnricher contextualRetrievalEnricher;
    private final QdrantPointAssembler pointAssembler;

    @Async("taskExecutor")
    public void ingest(Long docId, String taskId, String fileName, String category) {
        redisCacheService.setIngestionStatus(taskId, IngestionStatus.PROCESSING.name());

        try {
            // 1 & 2. Extract plain text — streams to avoid loading the full file into heap
            String text = textExtractor.extract(uploadStorage.pathOf(taskId));
            log.info("Tika parsed {} chars from '{}'", text.length(), fileName);

            // 3. Split via the first strategy that supports this input (markdown → semantic fallback),
            //    then stamp common metadata uniformly.
            ChunkSplitter splitter = chunkSplitterSelector.select(fileName, text);
            List<TextSegment> raw = splitter.split(text);
            log.info("{}: {} chunks from '{}' ({} chars)",
                    splitter.getClass().getSimpleName(), raw.size(), fileName, text.length());

            List<TextSegment> segments = new ArrayList<>(raw.size());
            for (int i = 0; i < raw.size(); i++) {
                TextSegment seg = raw.get(i);
                seg.metadata()
                    .put("docId", String.valueOf(docId))
                    .put("fileName", fileName)
                    .put("category", category)
                    .put("chunkIndex", String.valueOf(i))
                    .put("chunkTotal", String.valueOf(raw.size()));
                segments.add(seg);
            }

            // 4. Metadata enrichment — runs in parallel, before RAPTOR (indexed to original segments)
            List<ChunkMetadataEnricher.EnrichmentResult> enrichments = metadataEnricher.enrichAll(segments);
            log.info("Metadata enrichment done. chunks={} fileName={}", segments.size(), fileName);

            // 5. RAPTOR: prepend a document-level summary chunk for high-level recall.
            String summary = documentSummarizer.summarize(text, fileName, category);
            if (!summary.isBlank()) {
                Metadata summaryMeta = new Metadata();
                summaryMeta.put("docId", String.valueOf(docId));
                summaryMeta.put("fileName", fileName);
                summaryMeta.put("category", category);
                summaryMeta.put("chunkIndex", QdrantPointAssembler.SUMMARY_INDEX);
                summaryMeta.put("chunkTotal", String.valueOf(segments.size()));
                summaryMeta.put("chunkType", ChunkType.DOCUMENT_SUMMARY.getValue());
                segments.add(0, TextSegment.from(summary, summaryMeta));
            }

            // 6. Contextual Retrieval: text to embed per chunk (prefix prepended when enabled)
            List<String> embeddingTexts = contextualRetrievalEnricher.buildEmbeddingTexts(segments, text);

            // 7. Compute dense embeddings (OpenAI) — EmbeddingService handles batching
            List<float[]> embeddings = embeddingService.embedAll(embeddingTexts);

            // 8. Build Qdrant points (dense + sparse vectors, payload schema)
            List<QdrantSearchService.PointData> points =
                    pointAssembler.assemble(docId, fileName, segments, embeddings, enrichments);

            // 9. Upsert to Qdrant
            qdrantSearchService.upsertPoints(points);

            // 10. Mark done
            redisCacheService.setIngestionStatus(taskId, IngestionStatus.COMPLETED.name());
            documentMapper.updateIngestionResult(
                    docId, IngestionStatus.COMPLETED.name(), segments.size(), null);
            log.info("Ingestion completed: docId={}, chunks={}", docId, segments.size());

            // 11. Update IDF corpus with all chunk texts (including summary if present)
            sparseVectorizer.addChunksToCorpus(segments.stream().map(TextSegment::text).toList());

        } catch (Exception e) {
            log.error("Ingestion failed for docId={}", docId, e);
            redisCacheService.setIngestionStatus(taskId, IngestionStatus.FAILED.name());
            documentMapper.updateIngestionResult(
                    docId, IngestionStatus.FAILED.name(), 0, sanitizeErrorMessage(e));
        } finally {
            uploadStorage.delete(taskId);
        }
    }

    /** Truncates and strips control chars from exception messages before storing in DB. */
    private String sanitizeErrorMessage(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return "Unknown error";
        String cleaned = msg.replaceAll("[\\p{Cntrl}&&[^\t\n]]", " ");
        return cleaned.length() > 500 ? cleaned.substring(0, 500) : cleaned;
    }
}
