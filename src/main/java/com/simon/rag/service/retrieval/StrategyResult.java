package com.simon.rag.service.retrieval;

import com.simon.rag.service.vectorstore.QdrantSearchService.SearchHit;

import java.util.List;

/**
 * Per-stage output of one strategy run. Only {@code hits} reaches the LLM;
 * {@code candidates} (retrieved pool) and {@code scoped} (after the company filter,
 * before rerank) exist so the eval can attribute a missed chunk to the stage that dropped it.
 */
public record StrategyResult(
        List<SearchHit> candidates,
        List<SearchHit> scoped,
        List<RerankedHit> hits
) {}
