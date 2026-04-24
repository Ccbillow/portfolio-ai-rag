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

    @Value("${langchain4j.anthropic.chat-model-name:claude-haiku-4-5-20251001}")
    private String modelName;

    // ----------------------------------------------------------------
    //  Synchronous — Postman / REST clients
    // ----------------------------------------------------------------

    @Override
    public Vos.ChatResponse ask(Dtos.ChatRequest request, Long userId) {
        long start = System.currentTimeMillis();
        log.info("RAG ask: '{}'", request.getQuestion());

        RagProperties.Embedding cfg = ragProperties.getEmbedding();

        // 1. Cache check — disabled until production (rag.cache.enabled: false)
        String cacheKey = CacheConstant.CHAT_CACHE_PREFIX
                + DigestUtils.md5DigestAsHex(request.getQuestion().getBytes());
        if (ragProperties.getCache().isEnabled()) {
            Vos.ChatResponse cached = redisCacheService.getChatCache(cacheKey, start);
            if (cached != null) return cached;
        }

        // 2. Multi-query (3) → Qdrant → (Rerank) → expand neighbors
        List<String> queries = multiQueryExpander.expand(request.getQuestion());
        List<SearchHit> hits = retrieve(request.getQuestion(), queries, cfg);

        if (hits.isEmpty()) {
            return Vos.ChatResponse.builder()
                    .answer("I don't have specific information about that in my knowledge base. "
                            + "Please try rephrasing your question.")
                    .sources(List.of())
                    .modelUsed(modelName)
                    .latencyMs(System.currentTimeMillis() - start)
                    .build();
        }

        // 3. Build context and call Claude
        String context = hits.stream().map(SearchHit::text)
                .collect(Collectors.joining("\n\n---\n\n"));
        String answer = chatLanguageModel.generate(promptBuilder.build(request.getQuestion(), context));

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
            RagProperties.Embedding cfg = ragProperties.getEmbedding();

            // Multi-query retrieval (same as sync path, no cache for streaming)
            List<String> queries = multiQueryExpander.expand(request.getQuestion());
            List<SearchHit> hits = retrieve(request.getQuestion(), queries, cfg);
            log.info("Stream — {} hits after retrieval pipeline", hits.size());

            if (hits.isEmpty()) {
                emitter.send(SseEmitter.event().name("message")
                        .data("I don't have specific information about that in my knowledge base."));
                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
                return emitter;
            }

            emitter.send(SseEmitter.event().name("sources").data(toSources(hits)));

            String context = hits.stream().map(SearchHit::text)
                    .collect(Collectors.joining("\n\n---\n\n"));

            streamingChatLanguageModel.generate(
                    promptBuilder.build(request.getQuestion(), context),
                    new StreamingResponseHandler<AiMessage>() {
                        @Override
                        public void onNext(String token) {
                            try {
                                emitter.send(SseEmitter.event().name("message").data(token));
                            } catch (Exception e) {
                                emitter.completeWithError(e);
                            }
                        }

                        @Override
                        public void onComplete(Response<AiMessage> response) {
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
                            emitter.completeWithError(error);
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
     * Full retrieval pipeline: multi-query → Qdrant → optional Cohere rerank → expand neighbors.
     * With reranker: fetch candidateK, rerank to topN, then expand.
     * Without reranker: fetch topK, expand directly.
     */
    private List<SearchHit> retrieve(String question, List<String> queries, RagProperties.Embedding cfg) {
        RagProperties.Reranker rerankCfg = ragProperties.getReranker();

        if (rerankCfg.isEnabled()) {
            List<SearchHit> candidates = multiSearch(queries, cfg, rerankCfg.getCandidateK());
            if (candidates.isEmpty()) return List.of();
            List<SearchHit> reranked = cohereRerankService.rerank(question, candidates);
            return expandWithNeighbours(reranked);
        } else {
            return expandWithNeighbours(multiSearch(queries, cfg, cfg.getTopK()));
        }
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

    private float[] embedQuestion(String question) {
        return embeddingModel
                .embedAll(List.of(TextSegment.from(question)))
                .content()
                .get(0)
                .vector();
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
