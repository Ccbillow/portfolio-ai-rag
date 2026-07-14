package com.simon.rag.service.impl;

import com.simon.rag.comm.enums.BehavioralTag;
import com.simon.rag.comm.enums.ChunkType;
import com.simon.rag.comm.enums.IngestionStatus;
import com.simon.rag.comm.enums.IntentTag;
import com.simon.rag.config.RagProperties;
import com.simon.rag.dao.DocumentMapper;
import com.simon.rag.service.impl.SparseVectorizer.SparseVector;
import com.simon.rag.service.ingestion.ChunkSplitter;
import com.simon.rag.service.ingestion.ChunkSplitterSelector;
import com.simon.rag.service.ingestion.CompanyTagger;
import com.simon.rag.service.ingestion.ContextualRetrievalEnricher;
import com.simon.rag.service.ingestion.DocumentSummarizer;
import com.simon.rag.service.ingestion.TextExtractor;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    /** Sentinel chunkIndex marking the RAPTOR document-summary chunk. */
    private static final String SUMMARY_INDEX = "summary";
    /** Max segments per OpenAI embedding request. */
    private static final int EMBEDDING_BATCH_SIZE = 100;

    private final EmbeddingModel embeddingModel;
    private final QdrantSearchService qdrantSearchService;
    private final SparseVectorizer sparseVectorizer;
    private final DocumentMapper documentMapper;
    private final RedisCacheService redisCacheService;
    private final RagProperties ragProperties;
    private final ChunkMetadataEnricher metadataEnricher;
    private final TextExtractor textExtractor;
    private final ChunkSplitterSelector chunkSplitterSelector;
    private final CompanyTagger companyTagger;
    private final DocumentSummarizer documentSummarizer;
    private final ContextualRetrievalEnricher contextualRetrievalEnricher;

    @Async("taskExecutor")
    public void ingest(Long docId, String taskId, String fileName, String category) {
        redisCacheService.setIngestionStatus(taskId, IngestionStatus.PROCESSING.name());
        Path filePath = Path.of(ragProperties.getUpload().getDir(), taskId);

        try {
            // 1 & 2. Extract plain text — streams to avoid loading the full file into heap
            String text = textExtractor.extract(filePath);
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
                summaryMeta.put("chunkIndex", SUMMARY_INDEX);
                summaryMeta.put("chunkTotal", String.valueOf(segments.size()));
                summaryMeta.put("chunkType", ChunkType.DOCUMENT_SUMMARY.getValue());
                segments.add(0, TextSegment.from(summary, summaryMeta));
            }

            // 6. Contextual Retrieval: text to embed per chunk (prefix prepended when enabled)
            List<String> embeddingTexts = contextualRetrievalEnricher.buildEmbeddingTexts(segments, text);

            // 7. Compute dense embeddings (OpenAI) — batch to stay within per-request limits
            List<Embedding> embeddings = new ArrayList<>();
            for (int start = 0; start < embeddingTexts.size(); start += EMBEDDING_BATCH_SIZE) {
                List<TextSegment> batch = embeddingTexts
                        .subList(start, Math.min(start + EMBEDDING_BATCH_SIZE, embeddingTexts.size()))
                        .stream().map(TextSegment::from).toList();
                embeddings.addAll(embeddingModel.embedAll(batch).content());
            }

            // 8. Build Qdrant points with dense + sparse vectors
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
                List<String> companies = companyTagger.tag(fileName, seg);
                if (!companies.isEmpty()) payload.put("companies", companies);

                // Phase 12: enrichment metadata
                boolean isSummary = SUMMARY_INDEX.equals(meta.getString("chunkIndex"));
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
            try { Files.deleteIfExists(filePath); }
            catch (Exception ex) { log.warn("Failed to delete temp file {}: {}", filePath, ex.getMessage()); }
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
