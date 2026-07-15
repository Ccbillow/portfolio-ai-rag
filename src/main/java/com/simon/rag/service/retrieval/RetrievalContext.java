package com.simon.rag.service.retrieval;

import com.simon.rag.comm.enums.QuestionType;
import com.simon.rag.config.RagProperties;

import java.util.List;

/**
 * Immutable context passed to every {@code RetrievalStrategy}.
 * Built once in {@link RetrievalService} and shared across selection and execution.
 */
public record RetrievalContext(
        QuestionType questionType,
        String question,
        String retrievalQuery,
        List<String> queries,
        String focusCompany,
        List<String> siblings,
        RagProperties.Embedding embeddingCfg
) {}
