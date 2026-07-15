package com.simon.rag.service.ingestion;

import com.simon.rag.comm.enums.BehavioralTag;
import com.simon.rag.comm.enums.ChunkType;
import com.simon.rag.comm.enums.IntentTag;
import com.simon.rag.service.ingestion.ChunkMetadataEnricher.EnrichmentResult;
import com.simon.rag.service.vectorstore.QdrantSearchService.PointData;
import com.simon.rag.service.vectorstore.SparseVectorizer;
import com.simon.rag.service.vectorstore.SparseVectorizer.SparseVector;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Assembles Qdrant points from enriched segments: dense + BM25 sparse vectors
 * plus the retrieval payload (chunk metadata, companies, enrichment tags).
 * Keeps the payload-schema knowledge out of the ingestion orchestrator.
 */
@Component
@RequiredArgsConstructor
public class QdrantPointAssembler {

    /** Sentinel chunkIndex marking the RAPTOR document-summary chunk. */
    public static final String SUMMARY_INDEX = "summary";

    private final SparseVectorizer sparseVectorizer;
    private final CompanyTagger companyTagger;

    /**
     * @param segments    all chunks (summary chunk first when present)
     * @param embeddings  dense vectors aligned to {@code segments} order
     * @param enrichments enrichment results indexed by the ORIGINAL chunkIndex
     *                    (summary chunks fall back to {@link EnrichmentResult#empty()})
     */
    public List<PointData> assemble(Long docId, String fileName,
                                    List<TextSegment> segments,
                                    List<float[]> embeddings,
                                    List<EnrichmentResult> enrichments) {
        List<PointData> points = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            TextSegment seg = segments.get(i);
            SparseVector sparse = sparseVectorizer.vectorize(seg.text());

            Map<String, Object> vectors = new LinkedHashMap<>();
            vectors.put("dense", embeddings.get(i));
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
            EnrichmentResult enr;
            if (isSummary) {
                enr = EnrichmentResult.empty();
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

            points.add(new PointData(UUID.randomUUID().toString(), vectors, payload));
        }
        return points;
    }
}
