package com.simon.rag.service.impl;

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
    private final QdrantSearchService qdrantSearchService;   // direct REST — avoids lc4j cosine bug
    private final ChatLanguageModel chatLanguageModel;
    private final StreamingChatLanguageModel streamingChatLanguageModel;

    @Value("${rag.embedding.top-k:3}")
    private int topK;

    @Value("${rag.embedding.min-score:0.5}")
    private double minScore;

    // ----------------------------------------------------------------
    //  Synchronous — Postman / REST clients
    // ----------------------------------------------------------------

    @Override
    public Vos.ChatResponse ask(Dtos.ChatRequest request, Long userId) {
        long start = System.currentTimeMillis();
        log.info("RAG ask: '{}'", request.getQuestion());

        // Step 1: embed the question via OpenAI — 💰 tiny cost (~$0.000001)
        float[] queryVector = embedQuestion(request.getQuestion());

        // Step 2: search Qdrant via REST — free, local, uses Qdrant's cosine score
        List<SearchHit> hits = qdrantSearchService.search(queryVector, topK, minScore);

        if (hits.isEmpty()) {
            return Vos.ChatResponse.builder()
                    .answer("I don't have specific information about that in my knowledge base. "
                            + "Please try rephrasing your question.")
                    .sources(List.of())
                    .modelUsed("claude-haiku-4-5-20251001")
                    .latencyMs(System.currentTimeMillis() - start)
                    .build();
        }

        // Step 3: assemble context from retrieved chunks
        String context = hits.stream().map(SearchHit::text)
                .collect(Collectors.joining("\n\n---\n\n"));

        // Step 4: call Claude Haiku — 💰 main cost point
        String answer = chatLanguageModel.generate(buildPrompt(request.getQuestion(), context));

        return Vos.ChatResponse.builder()
                .answer(answer)
                .sources(toSources(hits))
                .modelUsed("claude-haiku-4-5-20251001")
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
            List<SearchHit> hits = qdrantSearchService.search(queryVector, topK, minScore);
            log.info("Stream — Qdrant returned {} hits", hits.size());

            if (hits.isEmpty()) {
                emitter.send(SseEmitter.event().name("message")
                        .data("I don't have specific information about that in my knowledge base."));
                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
                return emitter;
            }

            // Send sources immediately so frontend can render them while waiting for LLM
            emitter.send(SseEmitter.event().name("sources").data(toSources(hits)));

            String context = hits.stream().map(SearchHit::text)
                    .collect(Collectors.joining("\n\n---\n\n"));

            // Stream Claude tokens — each token fires onNext()
            streamingChatLanguageModel.generate(
                    buildPrompt(request.getQuestion(), context),
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
        // embedAll avoids the embed(String) zero-vector bug in langchain4j 0.35.0
        return embeddingModel
                .embedAll(List.of(TextSegment.from(question)))
                .content()
                .get(0)
                .vector();
    }

    private String buildPrompt(String question, String context) {
        return """
                You are Simon's professional AI assistant. \
                Answer questions about Simon's background, skills, and experience \
                based solely on the provided context. \
                Be concise and professional.

                Context from Simon's knowledge base:
                """ + context + """


                Question: """ + question + """


                Answer:""";
    }

    private List<Vos.SourceChunk> toSources(List<SearchHit> hits) {
        return hits.stream()
                .map(h -> Vos.SourceChunk.builder()
                        .fileName(h.fileName())
                        .category(h.category())
                        .score(h.score())
                        .contentPreview(h.text().substring(0, Math.min(200, h.text().length())))
                        .build())
                .collect(Collectors.toList());
    }
}
