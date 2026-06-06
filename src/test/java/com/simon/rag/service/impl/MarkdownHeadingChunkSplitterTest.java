package com.simon.rag.service.impl;

import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownHeadingChunkSplitterTest {

    private MarkdownHeadingChunkSplitter splitter;

    @BeforeEach
    void setUp() {
        splitter = new MarkdownHeadingChunkSplitter();
        ReflectionTestUtils.setField(splitter, "maxChunkSize", 1000);
        ReflectionTestUtils.setField(splitter, "chunkOverlap", 150);
    }

    // ── isMarkdown ──────────────────────────────────────────────────────────

    @Test
    void isMarkdown_withMdExtension_returnsTrue() {
        assertTrue(splitter.isMarkdown("AboutMe.md", "whatever"));
    }

    @Test
    void isMarkdown_withH2Heading_returnsTrue() {
        assertTrue(splitter.isMarkdown("foo.txt", "## Section One\nsome text"));
    }

    @Test
    void isMarkdown_withPlainText_returnsFalse() {
        assertFalse(splitter.isMarkdown("notes.txt", "just some text\nno headings"));
    }

    // ── split: heading boundaries ────────────────────────────────────────────

    @Test
    void split_byH2Headings_producesChunksWithMetadata() {
        String md = """
                # My Portfolio

                ## Experience
                Worked at Foo on backend systems.

                ## Skills
                Java, Spring, Kafka.
                """;

        List<TextSegment> chunks = splitter.split(md);

        assertEquals(2, chunks.size());

        assertEquals("Experience\n\nWorked at Foo on backend systems.", chunks.get(0).text());
        assertEquals("Experience", chunks.get(0).metadata("heading"));
        assertEquals("My Portfolio", chunks.get(0).metadata("doc_heading"));

        assertEquals("Skills\n\nJava, Spring, Kafka.", chunks.get(1).text());
        assertEquals("Skills", chunks.get(1).metadata("heading"));
        assertEquals("My Portfolio", chunks.get(1).metadata("doc_heading"));
    }

    @Test
    void split_byH3Headings_asBoundaries() {
        String md = """
                # Doc

                ### Sub A
                Content A here.

                ### Sub B
                Content B here.
                """;

        List<TextSegment> chunks = splitter.split(md);

        assertEquals(2, chunks.size());
        assertEquals("Sub A", chunks.get(0).metadata("heading"));
        assertEquals("Sub B", chunks.get(1).metadata("heading"));
    }

    @Test
    void split_contentBeforeFirstHeading_becomesChunkWithEmptyHeading() {
        String md = """
                # Doc

                Intro paragraph before any section.

                ## Section One
                Content one.
                """;

        List<TextSegment> chunks = splitter.split(md);

        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).text().contains("Intro paragraph"));
        assertEquals("", chunks.get(0).metadata("heading"));
    }

    @Test
    void split_singleSection_returnsOneChunk() {
        String md = """
                # Doc

                ## Only Section
                Some content.
                """;

        List<TextSegment> chunks = splitter.split(md);

        assertEquals(1, chunks.size());
        assertEquals("Only Section", chunks.get(0).metadata("heading"));
    }

    // ── split: edge cases ────────────────────────────────────────────────────

    @Test
    void split_emptyInput_returnsEmptyList() {
        List<TextSegment> chunks = splitter.split("");
        assertTrue(chunks.isEmpty());
    }

    @Test
    void split_noDocHeading_returnsEmptyDocHeading() {
        String md = """
                ## Section A
                Content A.
                """;

        List<TextSegment> chunks = splitter.split(md);

        assertEquals(1, chunks.size());
        assertEquals("", chunks.get(0).metadata("doc_heading"));
    }

    @Test
    void split_consecutiveHeadings_producesChunksWithNoBody() {
        String md = """
                # Doc

                ## Heading A
                ## Heading B
                Body under B.
                """;

        List<TextSegment> chunks = splitter.split(md);

        // "Heading A" section has empty body → not flushed because body is empty
        // "Heading B" section has body → flushed as one chunk
        assertEquals(1, chunks.size());
        assertEquals("Heading B", chunks.get(0).metadata("heading"));
        assertTrue(chunks.get(0).text().contains("Body under B"));
    }

    // ── split: oversized fallback ────────────────────────────────────────────

    @Test
    void split_oversizedSection_fallsBackToSentenceSplitter() {
        splitter = new MarkdownHeadingChunkSplitter();
        ReflectionTestUtils.setField(splitter, "maxChunkSize", 40);
        ReflectionTestUtils.setField(splitter, "chunkOverlap", 5);

        String md = """
                # Doc

                ## Long Section
                Sentence one is here. Sentence two is also here. Sentence three is here as well.
                """;

        List<TextSegment> chunks = splitter.split(md);

        // Should be split into multiple sub-chunks by sentence splitter
        assertTrue(chunks.size() > 1, "oversized section should produce multiple sub-chunks");

        // All sub-chunks should inherit heading metadata
        for (TextSegment chunk : chunks) {
            assertEquals("Long Section", chunk.metadata("heading"));
            assertEquals("Doc", chunk.metadata("doc_heading"));
        }
    }
}
