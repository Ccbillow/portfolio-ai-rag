package com.simon.rag.service.impl;

import com.simon.rag.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts text to a BM25-style sparse vector (no IDF — single-document scoring only).
 *
 * Token mapping: hash(term) % VOCAB_SIZE → index. Handles both CJK characters
 * (each char is a token) and Latin words (split on non-alphanumeric boundaries).
 *
 * Formula: score(t,d) = (k1+1)*tf / (k1*(1-b + b*|d|/avgLen) + tf)
 * where k1=1.2, b=0.75, avgLen derived from chunkSize (~5 chars per English token).
 */
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

    private final float avgTokens;

    public SparseVectorizer(RagProperties ragProperties) {
        this.avgTokens = ragProperties.getEmbedding().getChunkSize() / 5f;
    }

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
            float score = (K1 + 1) * freq / (K1 * (1 - B + B * docLen / avgTokens) + freq);
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
