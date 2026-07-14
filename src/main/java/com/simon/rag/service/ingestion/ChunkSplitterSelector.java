package com.simon.rag.service.ingestion;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Selects the first {@link ChunkSplitter} (in Spring @Order) that supports the input.
 * The semantic splitter is the lowest-priority fallback (supports everything).
 */
@Component
public class ChunkSplitterSelector {

    private final List<ChunkSplitter> splitters;

    public ChunkSplitterSelector(List<ChunkSplitter> splitters) {
        this.splitters = splitters;
    }

    public ChunkSplitter select(String fileName, String text) {
        return splitters.stream()
                .filter(s -> s.supports(fileName, text))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No ChunkSplitter supports: " + fileName));
    }
}
