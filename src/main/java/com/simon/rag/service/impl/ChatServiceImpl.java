package com.simon.rag.service.impl;

import com.simon.rag.comm.constant.CacheConstant;
import com.simon.rag.comm.enums.QuestionType;
import com.simon.rag.config.RagProperties;
import com.simon.rag.domain.dto.Dtos;
import com.simon.rag.domain.vo.Vos;
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

    @Value("${langchain4j.anthropic.chat-model-name:claude-haiku-4-5-20251001}")
    private String modelName;

    private static final String SESSION_LIMIT_MESSAGE =
            "You've reached the question limit for this session. Please refresh the page to start a new conversation.";

    /**
     * Shared result of the pre-LLM pipeline steps (gate checks + retrieval).
     * earlyReturn is non-null when the request should be answered without hitting the LLM or Qdrant.
     */
    private record PipelineResult(
            QuestionType questionType,
            String earlyReturn,
            String history,
            List<SearchHit> hits,
            String context,
            String focusCompany
    ) {
        boolean hasEarlyReturn() { return earlyReturn != null; }
        boolean noHits()         { return hits != null && hits.isEmpty(); }
    }

    /**
     * Runs all pre-LLM steps shared by ask() and askStream():
     * agent gate → session limit → history → retrieval → neighbour expansion.
     * Returns a PipelineResult; callers check hasEarlyReturn() / noHits() before proceeding to LLM.
     */
    private PipelineResult runPipeline(Dtos.ChatRequest request) {
        String question = request.getQuestion();

        QuestionType type = questionClassifier.classify(question);
        String earlyReturn = questionClassifier.earlyReturnMessage(type, question);
        if (earlyReturn != null) {
            log.info("Agent gate early return: type={}", type);
            return new PipelineResult(type, earlyReturn, null, null, null, null);
        }

        if (redisCacheService.isSessionLimitReached(request.getSessionId())) {
            log.info("Session turn limit reached: sessionId={}", request.getSessionId());
            return new PipelineResult(type, SESSION_LIMIT_MESSAGE, null, null, null, null);
        }

        RagProperties.Embedding cfg = ragProperties.getEmbedding();
        String history        = redisCacheService.getConversationHistory(request.getSessionId());
        String focusCompany   = promptBuilder.extractFocusCompany(question, history);
        String retrievalQuery = resolveRetrievalQuery(question, history, focusCompany);
        List<String> queries  = withSubProjectQueries(multiQueryExpander.expand(retrievalQuery), focusCompany);
        List<String> siblings = getSiblingCompanies(focusCompany);
        List<SearchHit> hits  = retrieve(retrievalQuery, queries, cfg, focusCompany, siblings);

        String context = hits.stream().map(SearchHit::text)
                .collect(Collectors.joining("\n\n---\n\n"));
        return new PipelineResult(type, null, history, hits, context, focusCompany);
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

        // 2. Gate checks + retrieval pipeline
        PipelineResult pipeline = runPipeline(request);
        if (pipeline.hasEarlyReturn()) {
            return Vos.ChatResponse.builder()
                    .answer(pipeline.earlyReturn())
                    .sources(List.of())
                    .modelUsed("rule-based")
                    .latencyMs(System.currentTimeMillis() - start)
                    .build();
        }
        if (pipeline.noHits()) {
            return Vos.ChatResponse.builder()
                    .answer("I don't have specific information about that in my knowledge base. "
                            + "Please try rephrasing your question.")
                    .sources(List.of())
                    .modelUsed(modelName)
                    .latencyMs(System.currentTimeMillis() - start)
                    .build();
        }

        // 3. Call Claude
        String answer;
        try {
            String prompt = promptBuilder.build(request.getQuestion(), pipeline.context(),
                    pipeline.questionType(), pipeline.history(), pipeline.focusCompany());
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
                .sources(toSources(pipeline.hits()))
                .modelUsed(modelName)
                .latencyMs(System.currentTimeMillis() - start)
                .build();

        // 4. Cache store
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
            } catch (Exception ignored) {}
        });
        try {
            // Gate checks + retrieval pipeline
            PipelineResult pipeline = runPipeline(request);
            if (pipeline.hasEarlyReturn()) {
                emitter.send(SseEmitter.event().name("message").data(pipeline.earlyReturn()));
                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
                return emitter;
            }
            log.info("Stream — {} hits after retrieval pipeline",
                    pipeline.hits() == null ? 0 : pipeline.hits().size());

            if (pipeline.noHits()) {
                emitter.send(SseEmitter.event().name("message")
                        .data("I don't have specific information about that in my knowledge base."));
                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
                return emitter;
            }

            emitter.send(SseEmitter.event().name("sources").data(toSources(pipeline.hits())));

            String question  = request.getQuestion();
            String sessionId = request.getSessionId();

            streamingChatLanguageModel.generate(
                    promptBuilder.build(question, pipeline.context(),
                            pipeline.questionType(), pipeline.history(), pipeline.focusCompany()),
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
     * Full retrieval pipeline:
     * - Hybrid ON : hybrid search (dense+BM25 via RRF) run for each expanded query, merged and deduped
     * - Hybrid OFF: multi-query dense search (original behavior)
     * Both paths optionally feed into Cohere Reranker, then expand neighbors.
     * focusCompany + siblings determine the company-scope filter applied post-expansion.
     */
    private List<SearchHit> retrieve(String question, List<String> queries,
                                      RagProperties.Embedding cfg, String focusCompany,
                                      List<String> siblings) {
        RagProperties.Reranker rerankCfg = ragProperties.getReranker();
        boolean useHybrid = ragProperties.getHybridSearch().isEnabled();
        int fetchLimit = rerankCfg.isEnabled() ? rerankCfg.getCandidateK() : cfg.getTopK();

        List<SearchHit> candidates;
        if (useHybrid) {
            LinkedHashMap<String, SearchHit> seen = new LinkedHashMap<>();
            for (String q : queries) {
                float[] dense = embedQuestion(q);
                SparseVectorizer.SparseVector sparse = sparseVectorizer.vectorize(q);
                if (!sparse.isEmpty()) {
                    qdrantSearchService.hybridSearch(dense, sparse, fetchLimit, focusCompany)
                            .forEach(h -> seen.putIfAbsent(h.docId() + ":" + h.chunkIndex(), h));
                } else {
                    log.warn("Sparse vector empty for query '{}', using dense only", q);
                    qdrantSearchService.search(dense, fetchLimit, cfg.getMinScore(), focusCompany)
                            .forEach(h -> seen.putIfAbsent(h.docId() + ":" + h.chunkIndex(), h));
                }
            }
            candidates = new ArrayList<>(seen.values());
        } else {
            candidates = multiSearch(queries, cfg, fetchLimit, focusCompany, siblings);
        }

        if (candidates.isEmpty()) return List.of();

        List<SearchHit> ranked = rerankCfg.isEnabled()
                ? cohereRerankService.rerank(question, candidates)
                : candidates;

        // Company-scoped filter: keep only chunks that belong to the focus company's "world".
        // A chunk is excluded if it carries a label for an unrelated independent company
        // (e.g. ["Alipay","OCBC","Sanofi","Deloitte"] is excluded for OCBC queries because
        //  Alipay is an independent company unrelated to the Deloitte/OCBC/Sanofi group).
        if (focusCompany != null) {
            Set<String> allowed = buildAllowedCompanies(focusCompany, siblings);
            return ranked.stream()
                    .filter(h -> {
                        List<String> cos = h.companies();
                        if (cos.isEmpty()) return true;
                        // Must have the focus company
                        if (cos.stream().noneMatch(c -> c.equalsIgnoreCase(focusCompany))) return false;
                        // Must not have any label outside the allowed set
                        return cos.stream().allMatch(
                                c -> allowed.stream().anyMatch(a -> a.equalsIgnoreCase(c)));
                    })
                    .collect(Collectors.toList());
        }
        return ranked;
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
     *
     * Why answer, not question:
     *   The answer contains concrete entities (company names, system names, tech terms)
     *   that anchor the embedding to the right topic far better than the vague Q.
     *
     * Example:
     *   history last A → "Insurance Portal System at Sinosig. Used Redis to cache credit checks..."
     *   question       → "How did you reduce cost in that system?"
     *   resolved       → "Insurance Portal System at Sinosig. Used Redis to cache credit checks...
     *                     How did you reduce cost in that system?"
     */
    private String resolveRetrievalQuery(String question, String history, String focusCompany) {
        if (history == null || history.isBlank()) return question;

        String lower = question.toLowerCase();
        boolean hasAmbiguity =
                lower.matches(".*(\\bit\\b|\\bthat\\b|\\bthere\\b|\\bthis\\b|\\bthey\\b|\\bthose\\b).*");
        if (!hasAmbiguity) return question;

        // Extract last Q and A from history
        String lastQuestion = "";
        String lastAnswer   = "";
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
    private List<String> withSubProjectQueries(List<String> queries, String focusCompany) {
        if (focusCompany == null) return queries;
        List<String> subgroups = ragProperties.getCompanySubgroups()
                .getOrDefault(focusCompany, List.of());
        if (subgroups.isEmpty()) return queries;
        String original = queries.get(0);
        String pattern  = "(?i)\\b" + java.util.regex.Pattern.quote(focusCompany) + "\\b";
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

    /** Returns sibling sub-companies for a sub-project focusCompany.
     *  Siblings are part of the same parent group and are ALLOWED in the post-filter.
     *  e.g. focusCompany=OCBC → siblings=[Sanofi] (both are Deloitte sub-projects). */
    private List<String> getSiblingCompanies(String focusCompany) {
        if (focusCompany == null) return List.of();
        return ragProperties.getCompanySubgroups().values().stream()
                .filter(subs -> subs.contains(focusCompany))
                .flatMap(java.util.Collection::stream)
                .filter(c -> !c.equals(focusCompany))
                .toList();
    }

    private boolean isQuotaError(Throwable e) {
        String msg = e.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("quota") || lower.contains("credit")
                || lower.contains("overload") || lower.contains("529")
                || (lower.contains("rate") && lower.contains("limit"));
    }

    private List<Vos.SourceChunk> toSources(List<SearchHit> hits) {
        return hits.stream()
                .map(h -> {
                    Long docId = null;
                    try {
                        if (h.docId() != null) docId = Long.parseLong(h.docId());
                    } catch (NumberFormatException ignored) {}
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
