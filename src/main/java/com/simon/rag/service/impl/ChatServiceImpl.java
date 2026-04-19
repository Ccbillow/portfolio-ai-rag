package com.simon.rag.service.impl;

import com.simon.rag.domain.dto.Dtos;
import com.simon.rag.domain.vo.Vos;
import com.simon.rag.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * ChatServiceImpl — stub ready to be filled in Phase 2.
 *
 * <p>Phase 2 will wire in:
 * <ul>
 *   <li>EmbeddingService    — embed the user question</li>
 *   <li>QdrantEmbeddingStore — vector similarity search</li>
 *   <li>LLM client           — generate grounded answer</li>
 *   <li>RedisTemplate        — cache identical queries</li>
 *   <li>ChatHistoryMapper    — persist Q&A pairs</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    @Override
    public Vos.ChatResponse ask(Dtos.ChatRequest request, Long userId) {
        // TODO Phase 2 — full RAG pipeline
        log.info("ask() called — pipeline not yet wired, returning stub");
        return Vos.ChatResponse.builder()
                .answer("RAG pipeline coming in Phase 2!")
                .modelUsed("stub")
                .latencyMs(0L)
                .build();
    }

    @Override
    public SseEmitter askStream(Dtos.ChatRequest request, Long userId) {
        // TODO Phase 2 — SSE streaming
        SseEmitter emitter = new SseEmitter(30_000L);
        try {
            emitter.send(SseEmitter.event()
                    .name("message")
                    .data("Streaming pipeline coming in Phase 2!"));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }
}