package com.simon.rag.service.impl;

import com.simon.rag.comm.constant.CacheConstant;
import com.simon.rag.config.RagProperties;
import com.simon.rag.domain.dto.Dtos;
import com.simon.rag.domain.vo.Vos;
import com.simon.rag.service.ChatService;
import com.simon.rag.service.impl.QdrantSearchService.SearchHit;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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

    // ----------------------------------------------------------------
    //  Synchronous — Postman / REST clients
    // ----------------------------------------------------------------

    @Override
    public Vos.ChatResponse ask(Dtos.ChatRequest request, Long userId) {
        long start = System.currentTimeMillis();
        log.info("RAG ask: '{}'", request.getQuestion());

        // 0. Agent Gate — classify before any pipeline cost
        QuestionType questionType = questionClassifier.classify(request.getQuestion());
        String earlyReturn = questionClassifier.earlyReturnMessage(questionType, request.getQuestion());
        if (earlyReturn != null) {
            log.info("Agent gate early return: type={}", questionType);
            return Vos.ChatResponse.builder()
                    .answer(earlyReturn)
                    .sources(List.of())
                    .modelUsed("rule-based")
                    .latencyMs(System.currentTimeMillis() - start)
                    .build();
        }

        // Session turn limit — checked before any embedding/LLM cost
        if (redisCacheService.isSessionLimitReached(request.getSessionId())) {
            log.info("Session turn limit reached: sessionId={}", request.getSessionId());
            return Vos.ChatResponse.builder()
                    .answer("You've reached the 30-question limit for this session. Please refresh the page to start a new conversation.")
                    .sources(List.of())
                    .modelUsed("rule-based")
                    .latencyMs(System.currentTimeMillis() - start)
                    .build();
        }

        RagProperties.Embedding cfg = ragProperties.getEmbedding();

        // 1. Cache check — disabled until production (rag.cache.enabled: false)
        String cacheKey = CacheConstant.CHAT_CACHE_PREFIX
                + DigestUtils.md5DigestAsHex(request.getQuestion().getBytes());
        if (ragProperties.getCache().isEnabled()) {
            Vos.ChatResponse cached = redisCacheService.getChatCache(cacheKey, start);
            if (cached != null) return cached;
        }

        // 2. Load history → resolve retrieval query → Multi-query → Qdrant → (Rerank) → expand
        String history        = redisCacheService.getConversationHistory(request.getSessionId());
        String retrievalQuery = resolveRetrievalQuery(request.getQuestion(), history);
        List<String> queries  = multiQueryExpander.expand(retrievalQuery);
        List<SearchHit> hits  = retrieve(retrievalQuery, queries, cfg);

        if (hits.isEmpty()) {
            return Vos.ChatResponse.builder()
                    .answer("I don't have specific information about that in my knowledge base. "
                            + "Please try rephrasing your question.")
                    .sources(List.of())
                    .modelUsed(modelName)
                    .latencyMs(System.currentTimeMillis() - start)
                    .build();
        }

        // 3. Build context and call Claude (prompt always uses original question + history)
        String context = hits.stream().map(SearchHit::text)
                .collect(Collectors.joining("\n\n---\n\n"));
        String answer;
        try {
            answer = chatLanguageModel.generate(
                    promptBuilder.build(request.getQuestion(), context, questionType, history));
        } catch (Exception e) {
            log.error("Claude generate error", e);
            return Vos.ChatResponse.builder()
                    .answer(isQuotaError(e)
                            ? "My AI assistant is currently unavailable due to usage limits. Please try again later."
                            : "Something went wrong on my end. Please try again in a moment.")
                    .sources(List.of())
                    .modelUsed(modelName)
                    .latencyMs(System.currentTimeMillis() - start)
                    .build();
        }
        redisCacheService.appendConversationHistory(
                request.getSessionId(), request.getQuestion(), answer);

        Vos.ChatResponse response = Vos.ChatResponse.builder()
                .answer(answer)
                .sources(toSources(hits))
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
    public SseEmitter askStream(Dtos.ChatRequest request, Long userId) {
        SseEmitter emitter = new SseEmitter(60_000L);
        try {
            // 0. Agent Gate
            QuestionType questionType = questionClassifier.classify(request.getQuestion());
            String earlyReturn = questionClassifier.earlyReturnMessage(questionType, request.getQuestion());
            if (earlyReturn != null) {
                log.info("Agent gate early return (stream): type={}", questionType);
                emitter.send(SseEmitter.event().name("message").data(earlyReturn));
                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
                return emitter;
            }

            // Session turn limit
            if (redisCacheService.isSessionLimitReached(request.getSessionId())) {
                log.info("Session turn limit reached (stream): sessionId={}", request.getSessionId());
                emitter.send(SseEmitter.event().name("message")
                        .data("You've reached the 30-question limit for this session. Please refresh the page to start a new conversation."));
                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
                return emitter;
            }

            RagProperties.Embedding cfg = ragProperties.getEmbedding();

            // Load history → resolve retrieval query → Multi-query retrieval
            String history        = redisCacheService.getConversationHistory(request.getSessionId());
            String retrievalQuery = resolveRetrievalQuery(request.getQuestion(), history);
            List<String> queries  = multiQueryExpander.expand(retrievalQuery);
            List<SearchHit> hits  = retrieve(retrievalQuery, queries, cfg);
            log.info("Stream — {} hits after retrieval pipeline", hits.size());

            if (hits.isEmpty()) {
                emitter.send(SseEmitter.event().name("message")
                        .data("I don't have specific information about that in my knowledge base."));
                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
                return emitter;
            }

            emitter.send(SseEmitter.event().name("sources").data(toSources(hits)));

            String context   = hits.stream().map(SearchHit::text)
                    .collect(Collectors.joining("\n\n---\n\n"));
            String question  = request.getQuestion();
            String sessionId = request.getSessionId();

            streamingChatLanguageModel.generate(
                    promptBuilder.build(question, context, questionType, history),
                    new StreamingResponseHandler<AiMessage>() {
                        private final StringBuilder fullAnswer = new StringBuilder();

                        @Override
                        public void onNext(String token) {
                            fullAnswer.append(token);
                            try {
                                emitter.send(SseEmitter.event().name("message").data(token));
                            } catch (Exception e) {
                                emitter.completeWithError(e);
                            }
                        }

                        @Override
                        public void onComplete(Response<AiMessage> response) {
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
                                        ? "My AI assistant is currently unavailable due to usage limits. Please try again later."
                                        : "Something went wrong on my end. Please try again in a moment.";
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
     * - Hybrid ON : single hybrid search (dense+BM25 via RRF) with larger candidate pool
     * - Hybrid OFF: multi-query dense search (original behavior)
     * Both paths optionally feed into Cohere Reranker, then expand neighbors.
     */
    private List<SearchHit> retrieve(String question, List<String> queries, RagProperties.Embedding cfg) {
        RagProperties.Reranker rerankCfg = ragProperties.getReranker();
        boolean useHybrid = ragProperties.getHybridSearch().isEnabled();
        int fetchLimit = rerankCfg.isEnabled() ? rerankCfg.getCandidateK() : cfg.getTopK();

        List<SearchHit> candidates;
        if (useHybrid) {
            float[] dense = embedQuestion(question);
            SparseVectorizer.SparseVector sparse = sparseVectorizer.vectorize(question);
            if (sparse.isEmpty()) {
                log.warn("Sparse vector empty for query '{}', falling back to dense search", question);
                candidates = multiSearch(queries, cfg, fetchLimit);
            } else {
                candidates = qdrantSearchService.hybridSearch(dense, sparse, fetchLimit);
            }
        } else {
            candidates = multiSearch(queries, cfg, fetchLimit);
        }

        if (candidates.isEmpty()) return List.of();

        List<SearchHit> ranked = rerankCfg.isEnabled()
                ? cohereRerankService.rerank(question, candidates)
                : candidates;

        return expandWithNeighbours(ranked);
    }

    /**
     * Embeds each query, searches Qdrant, merges by (docId:chunkIndex).
     * First occurrence wins (highest score), sorted desc, capped at limit.
     */
    private List<SearchHit> multiSearch(List<String> queries, RagProperties.Embedding cfg, int limit) {
        LinkedHashMap<String, SearchHit> seen = new LinkedHashMap<>();
        for (String query : queries) {
            float[] vector = embedQuestion(query);
            qdrantSearchService.search(vector, limit, cfg.getMinScore())
                    .forEach(hit -> seen.putIfAbsent(hit.docId() + ":" + hit.chunkIndex(), hit));
        }
        return seen.values().stream()
                .sorted(Comparator.comparingDouble(SearchHit::score).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * For each retrieved hit, also fetches the chunk immediately before and after it
     * (same document). Keeps insertion order so original hits appear first.
     */
    private List<SearchHit> expandWithNeighbours(List<SearchHit> hits) {
        LinkedHashMap<String, SearchHit> seen = new LinkedHashMap<>();
        for (SearchHit hit : hits) {
            seen.put(hit.docId() + ":" + hit.chunkIndex(), hit);
            for (int delta : new int[]{-1, 1}) {
                int neighbourIdx = hit.chunkIndex() + delta;
                if (neighbourIdx < 0) continue;
                String nKey = hit.docId() + ":" + neighbourIdx;
                if (!seen.containsKey(nKey)) {
                    List<SearchHit> neighbours =
                            qdrantSearchService.fetchByDocIdAndChunkIndex(hit.docId(), neighbourIdx);
                    if (!neighbours.isEmpty()) seen.put(nKey, neighbours.get(0));
                }
            }
        }
        return new ArrayList<>(seen.values());
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
    private String resolveRetrievalQuery(String question, String history) {
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
