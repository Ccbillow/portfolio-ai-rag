package com.simon.rag.service;

import com.simon.rag.domain.dto.Dtos;
import com.simon.rag.domain.vo.Vos;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Chat service — handles the full RAG query pipeline:
 * embed question → Qdrant search → LLM generation → return answer
 */
public interface ChatService {

    /**
     * Ask a question and get a complete (non-streaming) answer.
     * Answer is cached in Redis for identical questions.
     */
    Vos.ChatResponse ask(Dtos.ChatRequest request, Long userId);

    /**
     * Ask a question and stream the answer token-by-token via SSE.
     * Used for real-time chat UI experience.
     */
    SseEmitter askStream(Dtos.ChatRequest request, Long userId);
}