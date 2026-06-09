package com.simon.rag.service.impl;

import com.simon.rag.comm.constant.CacheConstant;
import com.simon.rag.comm.enums.IntentTag;
import com.simon.rag.comm.enums.QuestionType;
import com.simon.rag.config.RagProperties;
import com.simon.rag.domain.dto.Dtos;
import com.simon.rag.domain.vo.Vos;
import com.simon.rag.model.eval.RerankedHit;
import com.simon.rag.service.ChatService;
import com.simon.rag.service.impl.QdrantSearchService.SearchHit;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final EmbeddingModel embeddingModel;
    private final QdrantSearchService qdrantSearchService;
    private final ChatLanguageModel chatLanguageModel;
    private final StreamingChatLanguageModel streamingChatLanguageModel;
    private final RagProperties ragProperties;
    private final PromptBuilder promptBuilder;
    private final MultiQueryExpander multiQueryExpander;
    private final RedisCacheService redisCacheService;
    private final CohereRerankService cohereRerankService;
    private final SparseVectorizer sparseVectorizer;
    private final QuestionClassifier questionClassifier;
    private final QueryIntentClassifier queryIntentClassifier;

    @Value("${langchain4j.anthropic.chat-model-name:claude-haiku-4-5-20251001}")
    private String modelName;

    @Value("${claude.rate-limit-ms:3000}")
    private long claudeRateLimitMs;
    private final AtomicLong lastClaudeCall = new AtomicLong(0);

    private static final String SESSION_LIMIT_MESSAGE =
            "You've reached the question limit for this session. Please refresh the page to start a new conversation.";

    /**
     * Shared result of the pre-LLM pipeline steps (gate checks + retrieval).
     * earlyReturn is non-null when the request should be answered without hitting the LLM or Qdrant.
     */
    record RetrievalResult(
            QuestionType questionType,
            String earlyReturn,
            String history,
            List<SearchHit> candidates,
            List<RerankedHit> ranked,
            List<RerankedHit> hits,
            String context,
            String focusCompany,
            long retrieveMs,
            long rerankMs
    ) {
        boolean hasEarlyReturn() {
            return earlyReturn != null;
        }

        boolean noHits() {
            return hits != null && hits.isEmpty();
        }
    }

    /**
     * Pre-LLM retrieval pipeline shared by ask(), askStream(), and EvalChatServiceImpl.
     */
    RetrievalResult retrieve(String question, String sessionId) {

        // 1. Agent gate — rule-based classify, short-circuit INVALID/OUT_OF_SCOPE
        QuestionType type = questionClassifier.classify(question);
        String earlyReturn = questionClassifier.earlyReturnMessage(type, question);
        if (earlyReturn != null) {
            log.info("Agent gate early return: type={}", type);
            return new RetrievalResult(type, earlyReturn, null, null, null, null, null, null, 0, 0);
        }

        // 2. Query preparation — history, focus company, query expansion, sub-project queries
        RagProperties.Embedding cfg = ragProperties.getEmbedding();
        String history        = redisCacheService.getConversationHistory(sessionId);
        String focusCompany   = promptBuilder.extractFocusCompany(question, history);
        String retrievalQuery = resolveRetrievalQuery(question, history, focusCompany);
        List<String> queries  = withSubProjectQueries(multiQueryExpander.expand(retrievalQuery), focusCompany);
        List<String> siblings = getSiblingCompanies(focusCompany);

        // 3. Phase 12: classify intent on original question, then hybrid retrieval
        List<IntentTag> intentTags;
        if (type == QuestionType.FACTUAL) {
            intentTags = List.of();
            log.info("[Chat] FACTUAL question detected, skipping intent classification. q='{}'", question);
        } else {
            intentTags = queryIntentClassifier.classify(question);
            log.info("[Chat] sessionId={} intentTags={} question={}", sessionId, intentTags, question);
        }
        long t0 = System.currentTimeMillis();
        List<SearchHit> candidates = retrieveCandidates(queries, cfg, focusCompany, siblings, intentTags);
        log.info("[Chat] sessionId={} intentTags={} question={}",
                sessionId, intentTags,
                question.length() > 80 ? question.substring(0, 80) + "…" : question);
        long t1 = System.currentTimeMillis();

        // 4. Cohere rerank → company allowlist filter
        List<RerankedHit> ranked = type != QuestionType.FACTUAL
                ? cohereRerankService.rerank(retrievalQuery, candidates)
                : candidates.stream().map(h -> new RerankedHit(h, h.score())).toList();
        List<RerankedHit> hits = applyCompanyFilter(ranked, focusCompany, siblings);
        long t2 = System.currentTimeMillis();

        // 5. Join chunk texts into LLM context
        String context = hits.stream().map(rh -> rh.hit().text())
                .collect(Collectors.joining("\n\n---\n\n"));
        return new RetrievalResult(type, null, history, candidates, ranked, hits, context, focusCompany,
                t1 - t0, t2 - t1);
    }

    // ----------------------------------------------------------------
    //  Synchronous — Postman / REST clients
    // ----------------------------------------------------------------

    @Override
    public Vos.ChatResponse ask(Dtos.ChatRequest request) {
        long start = System.currentTimeMillis();
        log.info("RAG ask: '{}'", request.getQuestion());

        // 1. Cache check — disabled until production (rag.cache.enabled: false)
        String cacheKey = CacheConstant.CHAT_CACHE_PREFIX
                + DigestUtils.md5DigestAsHex(
                (request.getSessionId() + ":" + request.getQuestion()).getBytes(StandardCharsets.UTF_8));
        if (ragProperties.getCache().isEnabled()) {
            Vos.ChatResponse cached = redisCacheService.getChatCache(cacheKey, start);
            if (cached != null) return cached;
        }

        // 2. Session limit gate
        if (redisCacheService.isSessionLimitReached(request.getSessionId())) {
            return Vos.ChatResponse.builder()
                    .answer(SESSION_LIMIT_MESSAGE)
                    .sources(List.of())
                    .modelUsed("rule-based")
                    .latencyMs(System.currentTimeMillis() - start)
                    .build();
        }

        // 3. Retrieval pipeline
        RetrievalResult retrieval = retrieve(request.getQuestion(), request.getSessionId());
        if (retrieval.hasEarlyReturn()) {
            return Vos.ChatResponse.builder()
                    .answer(retrieval.earlyReturn())
                    .sources(List.of())
                    .modelUsed("rule-based")
                    .latencyMs(System.currentTimeMillis() - start)
                    .build();
        }
        if (retrieval.noHits()) {
            return Vos.ChatResponse.builder()
                    .answer("I don't have specific information about that in my knowledge base. "
                            + "Please try rephrasing your question.")
                    .sources(List.of())
                    .modelUsed(modelName)
                    .latencyMs(System.currentTimeMillis() - start)
                    .build();
        }

        // 4. Call Claude
        String answer;
        try {
            String prompt = promptBuilder.build(request.getQuestion(), retrieval.context(),
                    retrieval.questionType(), retrieval.history(), retrieval.focusCompany());
            throttleClaudeCall();
            Response<AiMessage> aiResponse = chatLanguageModel.generate(List.of(UserMessage.from(prompt)));
            answer = aiResponse.content().text();
            TokenUsage usage = aiResponse.tokenUsage();
            if (usage != null) {
                log.info("TOKEN sync — in={} out={} total={} | q='{}'",
                        usage.inputTokenCount(), usage.outputTokenCount(), usage.totalTokenCount(),
                        request.getQuestion().length() > 60
                                ? request.getQuestion().substring(0, 60) + "..." : request.getQuestion());
            }
        } catch (Exception e) {
            log.error("Claude generate error", e);
            return Vos.ChatResponse.builder()
                    .answer(isQuotaError(e)
                            ? "TBot is a bit overloaded right now — please try again in a moment."
                            : "Something went wrong — please try again.")
                    .sources(List.of())
                    .modelUsed(modelName)
                    .latencyMs(System.currentTimeMillis() - start)
                    .build();
        }
        redisCacheService.appendConversationHistory(
                request.getSessionId(), request.getQuestion(), answer);

        Vos.ChatResponse response = Vos.ChatResponse.builder()
                .answer(answer)
                .sources(toSources(retrieval.hits()))
                .modelUsed(modelName)
                .latencyMs(System.currentTimeMillis() - start)
                .build();

        // 5. Cache store
        if (ragProperties.getCache().isEnabled()) {
            redisCacheService.putChatCache(cacheKey, response);
        }

        return response;
    }

    // ----------------------------------------------------------------
    //  Streaming — SSE for frontend real-time chat UI
    // ----------------------------------------------------------------

    @Override
    public SseEmitter askStream(Dtos.ChatRequest request) {
        SseEmitter emitter = new SseEmitter(90_000L);
        emitter.onTimeout(() -> {
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data("TBot is taking longer than expected. Please try again."));
                emitter.complete();
            } catch (Exception ignored) {
            }
        });
        try {
            // 1. Session limit gate
            if (redisCacheService.isSessionLimitReached(request.getSessionId())) {
                emitter.send(SseEmitter.event().name("message").data(SESSION_LIMIT_MESSAGE));
                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
                return emitter;
            }

            // 2. Retrieval pipeline
            RetrievalResult retrieval = retrieve(request.getQuestion(), request.getSessionId());
            if (retrieval.hasEarlyReturn()) {
                emitter.send(SseEmitter.event().name("message").data(retrieval.earlyReturn()));
                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
                return emitter;
            }
            log.info("Stream — {} hits after retrieval pipeline",
                    retrieval.hits() == null ? 0 : retrieval.hits().size());

            if (retrieval.noHits()) {
                emitter.send(SseEmitter.event().name("message")
                        .data("I don't have specific information about that in my knowledge base."));
                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
                return emitter;
            }

            emitter.send(SseEmitter.event().name("sources").data(toSources(retrieval.hits())));

            String question = request.getQuestion();
            String sessionId = request.getSessionId();

            // 3. Call Claude (streaming)
            throttleClaudeCall();
            streamingChatLanguageModel.generate(
                    promptBuilder.build(question, retrieval.context(),
                            retrieval.questionType(), retrieval.history(), retrieval.focusCompany()),
                    new StreamingResponseHandler<AiMessage>() {
                        private final StringBuilder fullAnswer = new StringBuilder();

                        @Override
                        public void onNext(String token) {
                            fullAnswer.append(token);
                            try {
                                // Paragraph breaks → \\n\\n (frontend renders as blank line)
                                // Single newlines → space (prevents word concatenation in frontend HTML)
                                String sent = token.replace("\n\n", "\\n\\n").replace("\n", " ");
                                emitter.send(SseEmitter.event().name("message").data(sent));
                            } catch (Exception e) {
                                emitter.completeWithError(e);
                            }
                        }

                        @Override
                        public void onComplete(Response<AiMessage> response) {
                            TokenUsage usage = response.tokenUsage();
                            if (usage != null) {
                                log.info("TOKEN stream — in={} out={} total={} | q='{}'",
                                        usage.inputTokenCount(), usage.outputTokenCount(), usage.totalTokenCount(),
                                        question.length() > 60 ? question.substring(0, 60) + "..." : question);
                            }
                            redisCacheService.appendConversationHistory(
                                    sessionId, question, fullAnswer.toString());
                            try {
                                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                                emitter.complete();
                            } catch (Exception e) {
                                emitter.completeWithError(e);
                            }
                        }

                        @Override
                        public void onError(Throwable error) {
                            log.error("Claude streaming error", error);
                            try {
                                String msg = isQuotaError(error)
                                        ? "TBot is a bit overloaded right now — please try again in a moment."
                                        : "Something went wrong on my end. Please try again.";
                                emitter.send(SseEmitter.event().name("error").data(msg));
                                emitter.complete();
                            } catch (Exception ex) {
                                emitter.completeWithError(ex);
                            }
                        }
                    });

        } catch (Exception e) {
            log.error("askStream failed", e);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    // ----------------------------------------------------------------
    //  Retrieval pipeline
    // ----------------------------------------------------------------

    /**
     * Candidate gathering only — caller applies rerank + company filter.
     * Package-private so EvalChatServiceImpl (same package) can reuse the same code path.
     */
    List<SearchHit> retrieveCandidates(List<String> queries,
                                       RagProperties.Embedding cfg, String focusCompany,
                                       List<String> siblings, List<IntentTag> intentTags) {
        RagProperties.Reranker rerankCfg = ragProperties.getReranker();
        boolean useHybrid = ragProperties.getHybridSearch().isEnabled();
        int fetchLimit = rerankCfg.isEnabled() ? rerankCfg.getCandidateK() : cfg.getTopK();

        if (useHybrid) {
            LinkedHashMap<String, SearchHit> seen = new LinkedHashMap<>();
            for (String q : queries) {
                float[] dense = embedQuestion(q);
                SparseVectorizer.SparseVector sparse = sparseVectorizer.vectorize(q);
                if (!sparse.isEmpty()) {
                    qdrantSearchService.hybridSearchWithIntentFilter(
                                    dense, sparse, fetchLimit, focusCompany, intentTags)
                            .forEach(h -> seen.putIfAbsent(h.docId() + ":" + h.chunkIndex(), h));
                } else {
                    log.warn("Sparse vector empty for query '{}', using dense only", q);
                    qdrantSearchService.search(dense, fetchLimit, cfg.getMinScore(), focusCompany)
                            .forEach(h -> seen.putIfAbsent(h.docId() + ":" + h.chunkIndex(), h));
                }
            }
            return new ArrayList<>(seen.values());
        }
        return multiSearch(queries, cfg, fetchLimit, focusCompany, siblings);
    }

    /**
     * Company-scoped post-filter on reranked hits.
     * Package-private for EvalChatServiceImpl reuse.
     */
    List<RerankedHit> applyCompanyFilter(List<RerankedHit> ranked,
                                         String focusCompany, List<String> siblings) {
        if (focusCompany == null) return ranked;
        Set<String> allowed = buildAllowedCompanies(focusCompany, siblings);
        return ranked.stream()
                .filter(rh -> {
                    List<String> cos = rh.hit().companies();
                    if (cos.isEmpty()) return true;
                    if (cos.stream().noneMatch(c -> c.equalsIgnoreCase(focusCompany))) return false;
                    return cos.stream().allMatch(
                            c -> allowed.stream().anyMatch(a -> a.equalsIgnoreCase(c)));
                })
                .collect(Collectors.toList());
    }

    /**
     * Embeds each query, searches Qdrant, merges by (docId:chunkIndex).
     * First occurrence wins (highest score), sorted desc, capped at limit.
     */
    private List<SearchHit> multiSearch(List<String> queries, RagProperties.Embedding cfg,
                                        int limit, String focusCompany, List<String> siblings) {
        LinkedHashMap<String, SearchHit> seen = new LinkedHashMap<>();
        for (String query : queries) {
            float[] vector = embedQuestion(query);
            qdrantSearchService.search(vector, limit, cfg.getMinScore(), focusCompany)
                    .forEach(hit -> seen.putIfAbsent(hit.docId() + ":" + hit.chunkIndex(), hit));
        }
        return seen.values().stream()
                .sorted(Comparator.comparingDouble(SearchHit::score).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    // ----------------------------------------------------------------
    //  Other helpers
    // ----------------------------------------------------------------

    /**
     * When a follow-up question contains ambiguous pronouns (it/that/there/this/they/those),
     * prefix the retrieval query with the last answer snippet from history.
     * <p>
     * Why answer, not question:
     * The answer contains concrete entities (company names, system names, tech terms)
     * that anchor the embedding to the right topic far better than the vague Q.
     * <p>
     * Example:
     * history last A → "Insurance Portal System at Sinosig. Used Redis to cache credit checks..."
     * question       → "How did you reduce cost in that system?"
     * resolved       → "Insurance Portal System at Sinosig. Used Redis to cache credit checks...
     * How did you reduce cost in that system?"
     */
    String resolveRetrievalQuery(String question, String history, String focusCompany) {
        if (history == null || history.isBlank()) return question;

        String lower = question.toLowerCase();
        boolean hasAmbiguity =
                lower.matches(".*(\\bit\\b|\\bthat\\b|\\bthere\\b|\\bthis\\b|\\bthey\\b|\\bthose\\b).*");
        if (!hasAmbiguity) return question;

        // Extract last Q and A from history
        String lastQuestion = "";
        String lastAnswer = "";
        for (String line : history.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Q: ")) lastQuestion = trimmed.substring(3).strip();
            else if (trimmed.startsWith("A: ")) lastAnswer = trimmed.substring(3).strip();
        }

        // Prefer answer snippet — richer in concrete entities; fall back to last question
        String context = !lastAnswer.isBlank()
                ? lastAnswer.substring(0, Math.min(120, lastAnswer.length()))
                : lastQuestion;

        if (context.isBlank()) return question;

        // If the context snippet doesn't carry a company name (e.g. "15 months."),
        // prepend the focus company so the embedding anchors to the right topic.
        if (focusCompany != null && !context.toLowerCase().contains(focusCompany.toLowerCase())) {
            context = focusCompany + " " + context;
        }

        log.debug("Retrieval resolved — context: '{}' | question: '{}'", context, question);
        return context + " " + question;
    }

    private float[] embedQuestion(String question) {
        return embeddingModel
                .embedAll(List.of(TextSegment.from(question)))
                .content()
                .get(0)
                .vector();
    }

    /**
     * Deloitte delivered two client projects (OCBC and Sanofi).
     * When a question is about Deloitte, the original query alone scores
     * OCBC chunks much higher than Sanofi chunks (different vocabulary).
     * Adding explicit sub-project variants ensures both projects' chunks
     * are retrievable via multi-search.
     */
    List<String> withSubProjectQueries(List<String> queries, String focusCompany) {
        if (focusCompany == null) return queries;
        List<String> subgroups = ragProperties.getCompanySubgroups()
                .getOrDefault(focusCompany, List.of());
        if (subgroups.isEmpty()) return queries;
        String original = queries.get(0);
        String pattern = "(?i)\\b" + java.util.regex.Pattern.quote(focusCompany) + "\\b";
        List<String> expanded = new ArrayList<>(queries);
        for (String sub : subgroups) {
            String replaced = original.replaceAll(pattern, sub);
            if (!replaced.equals(original)) expanded.add(replaced);
        }
        return expanded;
    }

    /**
     * Returns the set of company labels that are "legitimate" for a given focus company.
     * Includes: the focus company itself, its siblings (other sub-projects of the same parent),
     * and any parent firm (e.g. Deloitte for OCBC/Sanofi).
     * Any chunk carrying a label outside this set gets filtered out of retrieval results.
     */
    private Set<String> buildAllowedCompanies(String focusCompany, List<String> siblings) {
        Set<String> allowed = new HashSet<>();
        allowed.add(focusCompany);
        allowed.addAll(siblings);
        ragProperties.getCompanySubgroups().forEach((parent, subs) -> {
            // Add parent firm when focusCompany is a sub-project (e.g. OCBC → Deloitte)
            if (subs.contains(focusCompany)) allowed.add(parent);
            // Add sub-projects when focusCompany is the parent (e.g. Deloitte → OCBC, Sanofi)
            if (parent.equalsIgnoreCase(focusCompany)) allowed.addAll(subs);
        });
        return allowed;
    }

    /**
     * Returns sibling sub-companies for a sub-project focusCompany.
     * Siblings are part of the same parent group and are ALLOWED in the post-filter.
     * e.g. focusCompany=OCBC → siblings=[Sanofi] (both are Deloitte sub-projects).
     */
    List<String> getSiblingCompanies(String focusCompany) {
        if (focusCompany == null) return List.of();
        return ragProperties.getCompanySubgroups().values().stream()
                .filter(subs -> subs.contains(focusCompany))
                .flatMap(java.util.Collection::stream)
                .filter(c -> !c.equals(focusCompany))
                .toList();
    }

    private void throttleClaudeCall() {
        while (true) {
            long prev = lastClaudeCall.get();
            long now = System.currentTimeMillis();
            long next = Math.max(now, prev + claudeRateLimitMs);
            if (lastClaudeCall.compareAndSet(prev, next)) {
                long waitMs = next - now;
                if (waitMs > 0) {
                    try {
                        Thread.sleep(waitMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return;
            }
        }
    }

    private boolean isQuotaError(Throwable e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("quota") || lower.contains("credit")
                || lower.contains("overload") || lower.contains("529")
                || (lower.contains("rate") && lower.contains("limit"));
    }

    private List<Vos.SourceChunk> toSources(List<RerankedHit> hits) {
        return hits.stream()
                .map(rh -> {
                    SearchHit h = rh.hit();
                    Long docId = null;
                    try {
                        if (h.docId() != null) docId = Long.parseLong(h.docId());
                    } catch (NumberFormatException ignored) {
                    }
                    return Vos.SourceChunk.builder()
                            .documentId(docId)
                            .fileName(h.fileName())
                            .category(h.category())
                            .score(h.score())
                            .contentPreview(h.text() != null
                                    ? h.text().substring(0, Math.min(200, h.text().length()))
                                    : "")
                            .build();
                })
                .collect(Collectors.toList());
    }
}
