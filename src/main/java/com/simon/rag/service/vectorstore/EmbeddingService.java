package com.simon.rag.service.vectorstore;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Single owner of text→dense-vector conversion, shared by the query side
 * (CandidateRetriever via HybridSearcher) and the ingestion side (IngestionRunner).
 * Batches large inputs to stay within the OpenAI per-request limit.
 */
@Component
@RequiredArgsConstructor
public class EmbeddingService {

    /** Max segments per OpenAI embedding request. */
    private static final int BATCH_SIZE = 100;

    private final EmbeddingModel embeddingModel;

    public float[] embed(String text) {
        return embedAll(List.of(text)).get(0);
    }

    public List<float[]> embedAll(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += BATCH_SIZE) {
            List<TextSegment> batch = texts
                    .subList(start, Math.min(start + BATCH_SIZE, texts.size()))
                    .stream().map(TextSegment::from).toList();
            embeddingModel.embedAll(batch).content()
                    .forEach(e -> vectors.add(e.vector()));
        }
        return vectors;
    }
}
