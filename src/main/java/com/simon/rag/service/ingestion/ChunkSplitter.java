package com.simon.rag.service.ingestion;

import dev.langchain4j.data.segment.TextSegment;

import java.util.List;

/**
 * Strategy for splitting a document's plain text into chunks.
 * Implementations declare which inputs they {@link #supports} and are selected
 * by {@link ChunkSplitterSelector}; adding a new format needs no orchestrator change.
 *
 * <p>Returned segments carry only splitter-specific metadata (e.g. markdown heading);
 * common metadata (docId/fileName/category/chunkIndex/chunkTotal) is stamped by the caller.
 */
public interface ChunkSplitter {

    boolean supports(String fileName, String text);

    List<TextSegment> split(String text);
}
