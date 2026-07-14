package com.simon.rag.service.ingestion;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Extracts plain text from an uploaded file via Apache Tika.
 * Stream-parses to avoid loading the full file into heap.
 */
@Component
public class TextExtractor {

    private final Tika tika = new Tika();

    public String extract(Path filePath) throws IOException, TikaException {
        try (InputStream in = Files.newInputStream(filePath)) {
            return normalize(tika.parseToString(in));
        }
    }

    /**
     * Normalizes Tika output: converts single newlines to spaces (prevents word
     * concatenation at paragraph boundaries in DOCX/PDF), preserves double newlines.
     */
    private String normalize(String text) {
        return text
                .replace("\r\n", "\n")
                .replaceAll("(?<!\n)\n(?!\n)", " ")
                .replaceAll("\n{3,}", "\n\n");
    }
}
