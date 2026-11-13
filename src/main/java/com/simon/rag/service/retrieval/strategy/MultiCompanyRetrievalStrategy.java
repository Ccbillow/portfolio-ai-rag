package com.simon.rag.service.retrieval.strategy;

import com.simon.rag.comm.enums.IntentTag;
import com.simon.rag.config.RagProperties;
import com.simon.rag.service.retrieval.RerankedHit;
import com.simon.rag.service.retrieval.CandidateRetriever;
import com.simon.rag.service.retrieval.QuerySignalClassifier;
import com.simon.rag.service.retrieval.RetrievalContext;
import com.simon.rag.service.vectorstore.QdrantSearchService.SearchHit;
import com.simon.rag.service.retrieval.query.QueryIntentClassifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MULTI_COMPANY — "past few jobs", "career highlights", "contributions across companies".
 * Per-company retrieval guarantees each company is represented; no global rerank
 * (cross-company reranking biases toward the richest-vocabulary company).
 * Diversity is enforced by construction here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiCompanyRetrievalStrategy implements RetrievalStrategy {

    private final CandidateRetriever candidateRetriever;
    private final QueryIntentClassifier queryIntentClassifier;
    private final QuerySignalClassifier querySignalClassifier;
    private final RagProperties ragProperties;

    @Override
    public String name() {
        return "MULTI_COMPANY";
    }

    @Override
    public List<RerankedHit> retrieve(RetrievalContext ctx) {
        // Behavioral-themed → use intent tags so companies without relevant behavioral
        // content naturally score lower during per-company retrieval.
        List<IntentTag> intents = querySignalClassifier.isBehavioralThemed(ctx.question())
                ? queryIntentClassifier.classify(ctx.question())
                : List.of();

        List<SearchHit> candidates = candidateRetriever.retrievePerCompany(
                ctx.queries().get(0), ctx.embeddingCfg(), intents);

        List<RerankedHit> hits = candidates.stream()
                .map(h -> new RerankedHit(h, h.score()))
                .collect(Collectors.toList());
        return enforceCompanyDiversity(hits, candidates);
    }

    /**
     * Ensures each known company has at least one chunk in the final context by appending
     * the best-scored chunk for any company not yet represented in hits.
     */
    private List<RerankedHit> enforceCompanyDiversity(List<RerankedHit> hits,
                                                      List<SearchHit> allCandidates) {
        Set<String> covered = hits.stream()
                .flatMap(rh -> rh.hit().companies().stream())
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        Set<String> missing = ragProperties.getCompanies().stream()
                .map(String::toLowerCase)
                .filter(c -> !covered.contains(c))
                .collect(Collectors.toSet());

        if (missing.isEmpty()) {
            log.debug("[Diversity] All companies covered: {}", covered);
            return hits;
        }

        List<RerankedHit> result = new ArrayList<>(hits);

        for (String missingCompany : missing) {
            allCandidates.stream()
                    .filter(h -> h.companies().stream()
                            .anyMatch(c -> c.equalsIgnoreCase(missingCompany)))
                    .filter(h -> result.stream().noneMatch(existing ->
                            existing.hit().docId().equals(h.docId()) &&
                                    existing.hit().chunkIndex() == h.chunkIndex()))
                    .findFirst()
                    .ifPresent(h -> {
                        result.add(new RerankedHit(h, h.score()));
                        log.info("[Diversity] Added '{}' chunk from raw candidates (score={})",
                                missingCompany, h.score());
                    });
        }

        log.info("[Diversity] hits {} → {} | missing was: {}", hits.size(), result.size(), missing);
        return result;
    }
}
