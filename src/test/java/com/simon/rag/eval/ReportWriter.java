package com.simon.rag.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ReportWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private ReportWriter() {}

    public static void writeJson(Object payload, Path target) {
        try {
            Files.createDirectories(target.getParent());
            MAPPER.writeValue(target.toFile(), payload);
        } catch (Exception e) {
            throw new RuntimeException("write " + target + " failed", e);
        }
    }

    public static void writeText(String body, Path target) {
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, body);
        } catch (Exception e) {
            throw new RuntimeException("write " + target + " failed", e);
        }
    }

    public static Path reportsDir() {
        return Path.of("src/test/resources/eval-reports");
    }
}
