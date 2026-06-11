package com.simon.rag.service.impl;

import dev.langchain4j.data.segment.TextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class MarkdownHeadingChunkSplitter {
    private static final Logger log = LoggerFactory.getLogger(MarkdownHeadingChunkSplitter.class);

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
                if (!currentBody.toString().isBlank()) {
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
        if (!currentBody.toString().isBlank()) {
            flush(result, currentHeading, currentBody.toString(), docHeading);
        }
        return result;
    }

    private void flush(List<TextSegment> result, String heading, String body, String docHeading) {
        String raw = heading.isBlank()
                ? body.strip()
                : heading + "\n\n" + body.strip();

        if (raw.isBlank()) return;

        String text = cleanMarkdown(raw);

        if (text.length() > maxChunkSize) {
            log.warn("[Splitter] Oversized chunk ({} chars > limit {}): '{}' — " +
                            "split '###' into two sections in the source file.",
                    text.length(), maxChunkSize,
                    heading.length() > 70 ? heading.substring(0, 70) + "…" : heading);
        }

        result.add(TextSegment.from(text,
                dev.langchain4j.data.document.Metadata.from("heading", heading)
                        .put("doc_heading", docHeading)));
    }

    /**
     * Strips markdown formatting noise while preserving semantic structure.
     * Removes: bold/italic markers, blockquote prefixes, horizontal rules,
     *          leading indent spaces, excessive blank lines.
     * Keeps: bullet markers, numbered lists, code spans, line breaks.
     */
    private static String cleanMarkdown(String text) {
        return text
                // **bold** → bold,  *italic* → italic
                .replaceAll("\\*\\*(.+?)\\*\\*", "$1")
                .replaceAll("\\*(?!\\s)(.+?)(?<!\\s)\\*", "$1")
                // > blockquote prefix
                .replaceAll("(?m)^>\\s*", "")
                // --- horizontal rule (whole line)
                .replaceAll("(?m)^-{3,}\\s*$", "")
                // leading spaces / tabs per line (de-indent)
                .replaceAll("(?m)^[ \\t]+", "")
                // collapse 3+ blank lines → single blank line
                .replaceAll("\n{3,}", "\n\n")
                .strip();
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
