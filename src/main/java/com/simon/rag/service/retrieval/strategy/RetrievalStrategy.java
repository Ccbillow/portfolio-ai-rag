package com.simon.rag.service.retrieval.strategy;

import com.simon.rag.model.eval.RerankedHit;
import com.simon.rag.service.retrieval.RetrievalContext;

import java.util.List;

/**
 * A retrieval strategy encapsulates how candidates are gathered, (optionally) reranked,
 * and post-filtered for one class of question intent. Selected by {@link RetrievalStrategySelector}.
 */
public interface RetrievalStrategy {

    /** Stable name used for logging and downstream prompt-mode resolution. */
    String name();

    List<RerankedHit> retrieve(RetrievalContext ctx);
}
