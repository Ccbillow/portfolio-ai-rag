package com.simon.rag.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.rag.config.RagProperties;
import com.simon.rag.model.eval.EvalCase;
import com.simon.rag.service.cache.RedisCacheService;
import com.simon.rag.service.retrieval.RetrievalResult;
import com.simon.rag.service.retrieval.RetrievalService;
import com.simon.rag.service.vectorstore.QdrantSearchService.SearchHit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Shared by the gold-label dump and the retrieval eval: ablation configs, chunk keys, per-case retrieval. */
final class RetrievalEvalSupport {

    private RetrievalEvalSupport() {}

    static final Path GOLD_LABELS = Path.of("src/test/resources/gold-labels.json");

    /** One row of the ablation table. FULL is the production configuration. */
    record Config(String name, boolean hybrid, boolean rerank, boolean intent) {}

    static final Config FULL = new Config("+rerank (prod)", true, true, false);

    static final List<Config> ABLATION = List.of(
            new Config("dense", false, false, false),
            new Config("hybrid", true, false, false),
            FULL,
            new Config("+intent filter", true, true, true));

    /** Runs {@code body} under {@code cfg}, then restores the original flags. */
    static <T> T with(RagProperties props, Config cfg, Supplier<T> body) {
        boolean h = props.getHybridSearch().isEnabled();
        boolean r = props.getReranker().isEnabled();
        boolean i = props.getIntentBoost().isEnabled();
        try {
            props.getHybridSearch().setEnabled(cfg.hybrid());
            props.getReranker().setEnabled(cfg.rerank());
            props.getIntentBoost().setEnabled(cfg.intent());
            return body.get();
        } finally {
            props.getHybridSearch().setEnabled(h);
            props.getReranker().setEnabled(r);
            props.getIntentBoost().setEnabled(i);
        }
    }

    /** Gold-label key. Survives re-ingestion (docId does not); breaks if the source doc is re-chunked. */
    static String key(SearchHit h) {
        return h.fileName() + "#" + h.chunkIndex();
    }

    /** Retrieval only — replays setup turns like EvalChatServiceImpl, never calls the answer LLM. */
    static RetrievalResult retrieve(EvalCase c, RetrievalService retrieval, RedisCacheService redis) {
        String sessionId = "eval-" + c.id() + "-" + UUID.randomUUID();
        try {
            if (c.setupTurns() != null) {
                c.setupTurns().forEach(t -> redis.appendConversationHistory(sessionId, t, ""));
            }
            return retrieval.retrieve(c.question(), sessionId);
        } finally {
            redis.clearSession(sessionId);
        }
    }

    /**
     * {@link #retrieve} for metric runs. Cohere failures (e.g. 429) silently fall back to
     * pre-rerank order, which would be scored as if reranking had happened — so a degraded
     * result is retried, and the run fails rather than report polluted numbers.
     */
    static RetrievalResult retrieveChecked(EvalCase c, Config cfg, RagProperties props,
                                           RetrievalService retrieval, RedisCacheService redis) {
        for (int attempt = 1; ; attempt++) {
            RetrievalResult r = with(props, cfg, () -> retrieve(c, retrieval, redis));
            if (!cfg.rerank() || !rerankFellBack(r)) return r;
            if (attempt == 4) throw new IllegalStateException("Rerank kept falling back for case " + c.id());
            try { Thread.sleep(20_000L * attempt); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    /** A real rerank replaces scores; the fallback copies the retrieval score into rerankScore. */
    private static boolean rerankFellBack(RetrievalResult r) {
        if (r.hasEarlyReturn() || r.stages().candidates().size() <= 2) return false;
        boolean reranks = Set.of("BEHAVIORAL", "FACTUAL_EXPERIENCE").contains(r.strategyName());
        return reranks && r.hits().stream().allMatch(rh -> rh.rerankScore() == rh.denseScore());
    }

    /** caseId → gold chunk keys. Empty list = reviewed, no chunk should be needed. */
    static Map<String, List<String>> loadGold() {
        try {
            return new ObjectMapper().readValue(Files.readString(GOLD_LABELS),
                    new TypeReference<Map<String, List<String>>>() {});
        } catch (Exception e) {
            throw new IllegalStateException(
                    GOLD_LABELS + " missing or unreadable — run `make eval-label` first", e);
        }
    }
}
