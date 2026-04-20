package com.simon.rag.service.impl;

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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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

    @Value("${langchain4j.anthropic.chat-model-name:claude-haiku-4-5-20251001}")
    private String modelName;

    // ----------------------------------------------------------------
    //  Synchronous — Postman / REST clients
    // ----------------------------------------------------------------

    @Override
    public Vos.ChatResponse ask(Dtos.ChatRequest request, Long userId) {
        long start = System.currentTimeMillis();
        log.info("RAG ask: '{}'", request.getQuestion());

        // 1. 💰 Embed question via OpenAI — ~$0.000001 per query
        float[] queryVector = embedQuestion(request.getQuestion());

        // 2. Search Qdrant for the top-k most relevant knowledge chunks
        RagProperties.Embedding cfg = ragProperties.getEmbedding();
        List<SearchHit> hits = qdrantSearchService.search(queryVector, cfg.getTopK(), cfg.getMinScore());

        if (hits.isEmpty()) {
            return Vos.ChatResponse.builder()
                    .answer("I don't have specific information about that in my knowledge base. "
                            + "Please try rephrasing your question.")
                    .sources(List.of())
                    .modelUsed(modelName)
                    .latencyMs(System.currentTimeMillis() - start)
                    .build();
        }

        // 3. Assemble retrieved chunks into a single context string
        String context = hits.stream().map(SearchHit::text)
                .collect(Collectors.joining("\n\n---\n\n"));

        // 4. 💰 Call Claude — main cost point (~$0.0001–0.0005 per answer)
        String answer = chatLanguageModel.generate(promptBuilder.build(request.getQuestion(), context));

        return Vos.ChatResponse.builder()
                .answer(answer)
                .sources(toSources(hits))
                .modelUsed(modelName)
                .latencyMs(System.currentTimeMillis() - start)
                .build();
    }

    // ----------------------------------------------------------------
    //  Streaming — SSE for frontend real-time chat UI
    // ----------------------------------------------------------------

    @Override
    public SseEmitter askStream(Dtos.ChatRequest request, Long userId) {
        SseEmitter emitter = new SseEmitter(60_000L);
        try {
            float[] queryVector = embedQuestion(request.getQuestion());

            RagProperties.Embedding cfg = ragProperties.getEmbedding();
            List<SearchHit> hits = qdrantSearchService.search(queryVector, cfg.getTopK(), cfg.getMinScore());
            log.info("Stream — Qdrant returned {} hits", hits.size());

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
    //  Helpers
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
