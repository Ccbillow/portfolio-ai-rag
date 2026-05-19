package com.simon.rag.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.rag.config.RagProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Converts text to a BM25 sparse vector with corpus IDF.
 *
 * Token mapping: hash(term) % VOCAB_SIZE → index. Handles both CJK characters
 * (each char is a token) and Latin words (split on non-alphanumeric boundaries).
 *
 * Formula: score(t,d) = IDF(t) * (k1+1)*tf / (k1*(1-b + b*|d|/avgLen) + tf)
 * IDF(t) = log(1 + (N+1) / (df(t)+1)), where N = total chunks indexed.
 * Corpus stats are persisted to {uploadDir}/idf_corpus.json and updated after each ingestion.
 * Falls back to IDF=1 (TF-only) when no corpus data is available.
 */
@Slf4j
@Component
public class SparseVectorizer {

    private static final int VOCAB_SIZE = 65_536;
    private static final float K1 = 1.2f;
    private static final float B  = 0.75f;

    // Common English words with no discriminative value for technical resume retrieval.
    // Filtering these makes rare technical terms (SofaBoot, MAT, reverse-match) dominate
    // BM25 scores instead of being diluted by high-frequency noise words.
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "a", "an", "and", "or", "but", "not", "no",
            "in", "on", "at", "to", "for", "of", "with", "by", "from", "into", "about",
            "is", "are", "was", "were", "be", "been", "being", "am",
            "have", "has", "had", "do", "does", "did", "will", "would", "could", "should",
            "it", "its", "this", "that", "these", "those",
            "i", "my", "me", "we", "our", "you", "your", "he", "she", "they", "them",
            "as", "if", "so", "also", "then", "than", "when", "which", "who", "what",
            "all", "each", "more", "some", "such", "there", "their", "they"
    );

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final float avgTokens;
    private final RagProperties ragProperties;
    // term-index → number of chunks containing that term
    private final Map<Integer, Integer> corpusDocFreq = new ConcurrentHashMap<>();
    private final AtomicInteger totalChunks = new AtomicInteger(0);
    private Path idfPath;

    public SparseVectorizer(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
        this.avgTokens = ragProperties.getEmbedding().getChunkSize() / 5f;
    }

    @PostConstruct
    void loadCorpus() {
        idfPath = Path.of(ragProperties.getUpload().getDir(), "idf_corpus.json");
        try {
            Files.createDirectories(idfPath.getParent());
            if (Files.exists(idfPath)) {
                CorpusState state = MAPPER.readValue(idfPath.toFile(), CorpusState.class);
                state.termDocFreq().forEach((k, v) -> corpusDocFreq.put(Integer.parseInt(k), v));
                totalChunks.set(state.totalChunks());
                log.info("IDF corpus loaded: {} term-indices, {} chunks", corpusDocFreq.size(), totalChunks.get());
            }
        } catch (Exception e) {
            log.warn("Could not load IDF corpus: {}", e.getMessage());
        }
    }

    /**
     * Updates corpus IDF statistics with newly ingested chunks, then persists to disk.
     * Called by IngestionRunner after each successful ingestion.
     * Note: deleting a document does not decrement corpus stats (acceptable for small corpora).
     */
    public synchronized void addChunksToCorpus(List<String> chunkTexts) {
        for (String text : chunkTexts) {
            Set<Integer> seen = new HashSet<>();
            for (String token : tokenize(text)) {
                seen.add((token.hashCode() & Integer.MAX_VALUE) % VOCAB_SIZE);
            }
            seen.forEach(idx -> corpusDocFreq.merge(idx, 1, Integer::sum));
            totalChunks.incrementAndGet();
        }
        try {
            Map<String, Integer> serializable = new HashMap<>();
            corpusDocFreq.forEach((k, v) -> serializable.put(String.valueOf(k), v));
            MAPPER.writeValue(idfPath.toFile(), new CorpusState(serializable, totalChunks.get()));
        } catch (IOException e) {
            log.warn("Could not persist IDF corpus: {}", e.getMessage());
        }
    }

    private float idf(int termIdx) {
        int n = totalChunks.get();
        if (n == 0) return 1.0f; // no corpus data yet → fall back to TF-only
        int df = corpusDocFreq.getOrDefault(termIdx, 0);
        return (float) Math.log(1.0 + (n + 1.0) / (df + 1.0));
    }

    private record CorpusState(Map<String, Integer> termDocFreq, int totalChunks) {}

    public record SparseVector(List<Integer> indices, List<Float> values) {
        public boolean isEmpty() { return indices.isEmpty(); }
    }

    public SparseVector vectorize(String text) {
        List<String> tokens = tokenize(text);
        if (tokens.isEmpty()) return new SparseVector(List.of(), List.of());

        Map<String, Integer> tf = new HashMap<>();
        for (String t : tokens) tf.merge(t, 1, Integer::sum);

        float docLen = tokens.size();
        Map<Integer, Float> sparse = new HashMap<>();
        for (Map.Entry<String, Integer> e : tf.entrySet()) {
            // & MAX_VALUE masks the sign bit so Integer.MIN_VALUE.hashCode() never produces a negative index
            int idx = (e.getKey().hashCode() & Integer.MAX_VALUE) % VOCAB_SIZE;
            float freq = e.getValue();
            float tfScore = (K1 + 1) * freq / (K1 * (1 - B + B * docLen / avgTokens) + freq);
            float score = idf(idx) * tfScore;
            sparse.merge(idx, score, Float::sum);
        }

        List<Integer> indices = new ArrayList<>(sparse.keySet());
        List<Float> values = new ArrayList<>();
        for (int i : indices) values.add(sparse.get(i));
        return new SparseVector(indices, values);
    }

    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder latin = new StringBuilder();

        for (char c : text.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                flushLatin(latin, tokens);
                tokens.add(String.valueOf(c));
            } else if (Character.isLetterOrDigit(c)) {
                latin.append(Character.toLowerCase(c));
            } else {
                flushLatin(latin, tokens);
            }
        }
        flushLatin(latin, tokens);
        return tokens;
    }

    private void flushLatin(StringBuilder buf, List<String> out) {
        if (buf.length() >= 2) {
            String token = buf.toString();
            if (!STOP_WORDS.contains(token)) out.add(token);
        }
        buf.setLength(0);
    }
}
