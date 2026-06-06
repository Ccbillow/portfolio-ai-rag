package com.simon.rag.service.impl;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentBySentenceSplitter;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class MarkdownHeadingChunkSplitter {

    private static final Pattern H2_PATTERN = Pattern.compile("(?m)^(## .+)$");

    @Value("${rag.splitter.chunk-size:1000}")
    private int maxChunkSize;

    @Value("${rag.splitter.chunk-overlap:150}")
    private int chunkOverlap;

    public boolean isMarkdown(String fileName, String rawText) {
        return fileName.toLowerCase().endsWith(".md")
                || H2_PATTERN.matcher(rawText).find();
    }

    public List<TextSegment> split(String rawText) {
        List<TextSegment> result = new ArrayList<>();
        String docHeading = extractDocHeading(rawText);
        String[] lines = rawText.split("\n");

        String currentHeading = "";
        StringBuilder currentBody = new StringBuilder();

        for (String line : lines) {
            if (line.startsWith("### ") || line.startsWith("## ")) {
                if (!currentBody.isEmpty()) {
                    flush(result, currentHeading, currentBody.toString(), docHeading);
                }
                currentHeading = line.replaceFirst("^#{2,3} ", "").trim();
                currentBody = new StringBuilder();
            } else if (line.startsWith("# ") && !line.startsWith("## ")) {
                // doc root heading — skip as chunk boundary
            } else {
                currentBody.append(line).append("\n");
            }
        }
        if (!currentBody.isEmpty()) {
            flush(result, currentHeading, currentBody.toString(), docHeading);
        }
        return result;
    }

    private void flush(List<TextSegment> result,
                       String heading,
                       String body,
                       String docHeading) {
        String text = heading.isBlank()
                ? body.strip()
                : heading + "\n\n" + body.strip();

        if (text.length() <= maxChunkSize) {
            TextSegment segment = TextSegment.from(text,
                    dev.langchain4j.data.document.Metadata.from("heading", heading)
                            .put("doc_heading", docHeading));
            result.add(segment);
        } else {
            DocumentSplitter sentenceSplitter = new DocumentBySentenceSplitter(
                    maxChunkSize, chunkOverlap);
            Document doc = Document.from(text);
            List<TextSegment> subChunks = sentenceSplitter.split(doc);
            subChunks.forEach(sc ->
                    result.add(TextSegment.from(sc.text(),
                            sc.metadata()
                              .put("heading", heading)
                              .put("doc_heading", docHeading))));
        }
    }

    private String extractDocHeading(String rawText) {
        for (String line : rawText.split("\n")) {
            if (line.startsWith("# ") && !line.startsWith("## ")) {
                return line.substring(2).trim();
            }
        }
        return "";
    }
}
