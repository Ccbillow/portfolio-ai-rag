package com.simon.rag.service.impl;

import com.simon.rag.comm.enums.IngestionStatus;
import com.simon.rag.config.RagProperties;
import com.simon.rag.dao.DocumentMapper;
import com.simon.rag.service.impl.SparseVectorizer.SparseVector;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentByCharacterSplitter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    private final QdrantSearchService qdrantSearchService;
    private final SparseVectorizer sparseVectorizer;
    private final DocumentMapper documentMapper;
    private final RedisCacheService redisCacheService;
    private final RagProperties ragProperties;

    private final Tika tika = new Tika();
    private DocumentByCharacterSplitter splitter;

    @PostConstruct
    void initSplitter() {
        RagProperties.Embedding cfg = ragProperties.getEmbedding();
        splitter = new DocumentByCharacterSplitter(cfg.getChunkSize(), cfg.getChunkOverlap());
        log.info("DocumentSplitter initialized: chunkSize={}, chunkOverlap={}",
                cfg.getChunkSize(), cfg.getChunkOverlap());
    }

    @Async
    public void ingest(Long docId, String taskId, String fileName, String category) {
        redisCacheService.setIngestionStatus(taskId, IngestionStatus.PROCESSING.name());
        Path filePath = Path.of(ragProperties.getUpload().getDir(), taskId);

        try {
            // 1. Read file from disk
            byte[] fileBytes = Files.readAllBytes(filePath);

            // 2. Parse to plain text and normalize whitespace
            String text = normalizeText(tika.parseToString(new ByteArrayInputStream(fileBytes)));
            log.info("Tika parsed {} chars from '{}'", text.length(), fileName);

            // 3. Split into overlapping chunks
            List<TextSegment> rawSegments =
                    splitter.split(dev.langchain4j.data.document.Document.from(text));

            // 4. Attach metadata
            List<TextSegment> segments = new ArrayList<>();
            for (int i = 0; i < rawSegments.size(); i++) {
                Metadata meta = new Metadata();
                meta.put("docId", String.valueOf(docId));
                meta.put("fileName", fileName);
                meta.put("category", category);
                meta.put("chunkIndex", String.valueOf(i));
                meta.put("chunkTotal", String.valueOf(rawSegments.size()));
                segments.add(TextSegment.from(rawSegments.get(i).text(), meta));
            }

            // 5. Compute dense embeddings (OpenAI)
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

            // Filename-level companies — non-empty means the whole file is scoped to those companies.
            List<String> fileCompanies = extractCompaniesFromText(fileName);

            // 6. Build Qdrant points with dense + sparse vectors
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

                // Filename companies take priority; fall back to scanning the chunk text itself.
                List<String> companies = fileCompanies.isEmpty()
                        ? extractCompaniesFromText(seg.text())
                        : fileCompanies;
                if (!companies.isEmpty()) payload.put("companies", companies);

                points.add(new QdrantSearchService.PointData(
                        UUID.randomUUID().toString(), vectors, payload));
            }

            // 7. Upsert to Qdrant
            qdrantSearchService.upsertPoints(points);

            // 8. Mark done
            redisCacheService.setIngestionStatus(taskId, IngestionStatus.COMPLETED.name());
            documentMapper.updateIngestionResult(
                    docId, IngestionStatus.COMPLETED.name(), segments.size(), null);
            log.info("Ingestion completed: docId={}, chunks={}", docId, segments.size());

            Files.deleteIfExists(filePath);

        } catch (Exception e) {
            log.error("Ingestion failed for docId={}", docId, e);
            redisCacheService.setIngestionStatus(taskId, IngestionStatus.FAILED.name());
            documentMapper.updateIngestionResult(
                    docId, IngestionStatus.FAILED.name(), 0, e.getMessage());
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
     * Normalizes Tika output: converts single newlines to spaces (prevents word
     * concatenation at paragraph boundaries in DOCX/PDF), preserves double newlines.
     */
    private String normalizeText(String text) {
        return text
                .replace("\r\n", "\n")
                .replaceAll("(?<!\n)\n(?!\n)", " ")
                .replaceAll("\n{3,}", "\n\n");
    }
}
