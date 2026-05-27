package com.simon.rag.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.rag.model.eval.EvalCase;

import java.io.InputStream;
import java.util.List;

public final class CaseLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CaseLoader() {}

    public static List<EvalCase> load() {
        try (InputStream is = CaseLoader.class.getResourceAsStream("/eval-set.json")) {
            if (is == null) throw new IllegalStateException("eval-set.json not on classpath");
            return MAPPER.readValue(is, new TypeReference<List<EvalCase>>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to load eval-set.json", e);
        }
    }
}
