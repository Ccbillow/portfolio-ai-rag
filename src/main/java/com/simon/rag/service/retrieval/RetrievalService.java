package com.simon.rag.service.retrieval;

import com.simon.rag.comm.enums.QuestionType;
import com.simon.rag.config.RagProperties;
import com.simon.rag.service.cache.ConversationHistory;
import com.simon.rag.service.cache.RedisCacheService;
import com.simon.rag.service.retrieval.query.MultiQueryExpander;
import com.simon.rag.service.retrieval.query.QuestionClassifier;
import com.simon.rag.service.retrieval.strategy.RetrievalStrategy;
import com.simon.rag.service.retrieval.strategy.RetrievalStrategySelector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pre-LLM RAG retrieval orchestrator, shared by ask(), askStream(), and the eval path.
 *
 * <pre>
 * Question
 *   → Rule gate (INVALID/OUT_OF_SCOPE → early return)
 *   → Query prep (history, focusCompany, queries, siblings)
 *   → strategy = selector.select(ctx)   (see retrieval.strategy)
 *   → strategy.retrieve(ctx) → List&lt;RerankedHit&gt;
 *   → context assembly + coverage log
 *   → RetrievalResult
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    private final QuestionClassifier questionClassifier;
    private final RagProperties ragProperties;
    private final RedisCacheService redisCacheService;
    private final MultiQueryExpander multiQueryExpander;
    private final CompanyScopeResolver companyScopeResolver;
    private final RetrievalStrategySelector strategySelector;

    public RetrievalResult retrieve(String question, String sessionId) {

        // 1. Rule Gate
        QuestionType type = questionClassifier.classify(question);
        String earlyReturn = questionClassifier.earlyReturnMessage(type, question);
        if (earlyReturn != null) {
            log.info("Rule gate early return: type={}", type);
            return RetrievalResult.earlyReturn(type, earlyReturn);
        }

        // 2. Query Preparation
        RagProperties.Embedding cfg = ragProperties.getEmbedding();
        ConversationHistory history = redisCacheService.getConversationHistory(sessionId);
        String focusCompany   = companyScopeResolver.extractFocusCompany(question, history);
        String retrievalQuery = resolveRetrievalQuery(question, history, focusCompany);
        List<String> queries  = companyScopeResolver.withSubProjectQueries(
                multiQueryExpander.expand(retrievalQuery), focusCompany);
        List<String> siblings = companyScopeResolver.getSiblingCompanies(focusCompany);

        // 3. Strategy Selection + Execution
        RetrievalContext ctx = new RetrievalContext(type, question, retrievalQuery, queries, focusCompany, siblings, cfg);
        RetrievalStrategy strategy = strategySelector.select(ctx);

        long t0 = System.currentTimeMillis();
        List<RerankedHit> hits = strategy.retrieve(ctx);
        long t1 = System.currentTimeMillis();

        // 4. Context Assembly + Coverage Log
        String context = hits.stream()
                .map(rh -> rh.hit().text())
                .collect(Collectors.joining("\n\n---\n\n"));
        Set<String> contextCompanies = hits.stream()
                .flatMap(rh -> rh.hit().companies().stream())
                .collect(Collectors.toSet());
        log.info("[Chat] questionType={} strategy={} context_companies={} focusCompany={} hits={} q='{}'",
                type, strategy.name(), contextCompanies, focusCompany, hits.size(),
                question.length() > 80 ? question.substring(0, 80) + "…" : question);

        return RetrievalResult.of(type, history.promptText(), hits, context,
                focusCompany, t1 - t0, strategy.name());
    }

    /**
     * When a follow-up question contains ambiguous pronouns (it/that/there/this/they/those),
     * prefix the retrieval query with the last answer snippet from history — the answer
     * carries concrete entities that anchor the embedding far better than the vague question.
     */
    String resolveRetrievalQuery(String question, ConversationHistory history, String focusCompany) {
        if (history.isEmpty()) return question;

        String lower = question.toLowerCase();
        boolean hasAmbiguity =
                lower.matches(".*(\\bit\\b|\\bthat\\b|\\bthere\\b|\\bthis\\b|\\bthey\\b|\\bthose\\b).*");
        if (!hasAmbiguity) return question;

        String lastAnswer = history.lastAnswer();
        String context = !lastAnswer.isBlank()
                ? lastAnswer.substring(0, Math.min(120, lastAnswer.length()))
                : history.lastQuestion();

        if (context.isBlank()) return question;

        if (focusCompany != null && !context.toLowerCase().contains(focusCompany.toLowerCase())) {
            context = focusCompany + " " + context;
        }

        log.debug("Retrieval resolved — context: '{}' | question: '{}'", context, question);
        return context + " " + question;
    }
}
