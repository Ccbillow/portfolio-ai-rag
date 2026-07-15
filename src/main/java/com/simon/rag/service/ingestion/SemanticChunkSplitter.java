package com.simon.rag.service.ingestion;

import com.simon.rag.config.RagProperties;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Semantic chunking: splits text on paragraph boundaries (\n\n), merges short consecutive
 * paragraphs, and falls back to sentence-level splitting for oversized paragraphs.
 * Prevents mid-sentence cuts produced by fixed character splitting.
 *
 * <p>Lowest-priority fallback {@link ChunkSplitter} — used for any non-markdown document.
 */
@Component
@Order(2)
@RequiredArgsConstructor
public class SemanticChunkSplitter implements ChunkSplitter {

    private final RagProperties ragProperties;

    @Override
    public boolean supports(String fileName, String text) {
        return true;
    }

    @Override
    public List<TextSegment> split(String text) {
        RagProperties.Embedding cfg = ragProperties.getEmbedding();
        return split(text, cfg.getChunkSize(), cfg.getChunkOverlap()).stream()
                .map(TextSegment::from)
                .toList();
    }

    private List<String> split(String text, int targetSize, int overlap) {
        List<String> paragraphs = Arrays.stream(text.split("\n\n"))
                .map(String::strip)
                .filter(s -> !s.isBlank())
                .toList();

        List<String> chunks = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        for (String para : paragraphs) {
            if (para.length() > targetSize) {
                if (!buf.isEmpty()) { chunks.add(buf.toString()); buf.setLength(0); }
                chunks.addAll(sentenceSplit(para, targetSize, overlap));
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

    /**
     * Splits text at sentence boundaries into chunks of at most {@code targetSize} chars,
     * carrying a trailing-fragment overlap between consecutive chunks.
     * Pure function — also used by {@link MarkdownHeadingChunkSplitter} for oversized sections.
     */
    static List<String> sentenceSplit(String text, int targetSize, int overlap) {
        List<String> out = new ArrayList<>();
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
        return out;
    }

    /** Returns the trailing sentence fragment of `chunk` up to `overlap` chars for context continuity. */
    private static String trailingOverlap(String chunk, int overlap) {
        if (overlap <= 0 || chunk.length() <= overlap) return "";
        String tail = chunk.substring(chunk.length() - overlap);
        int sentenceStart = tail.indexOf(". ");
        return sentenceStart >= 0 ? tail.substring(sentenceStart + 2) : tail;
    }
}
