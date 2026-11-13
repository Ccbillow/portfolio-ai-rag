package com.simon.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.rag.dao.ChatHistoryMapper;
import com.simon.rag.domain.entity.ChatHistory;
import com.simon.rag.service.retrieval.RerankedHit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Persists answered questions to {@code rag_chat_history} off the request thread.
 * A failed write is logged and never affects the answer.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatHistoryRecorder {

    private final ChatHistoryMapper chatHistoryMapper;
    private final ObjectMapper objectMapper;

    @Async("taskExecutor")
    public void record(String sessionId, String question, String answer,
                       List<RerankedHit> hits, String modelUsed, long latencyMs) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            String docIds = hits.stream()
                    .map(rh -> rh.hit().docId())
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.joining(","));
            String scores = hits.isEmpty() ? null : objectMapper.writeValueAsString(
                    hits.stream().map(RerankedHit::rerankScore).toList());

            chatHistoryMapper.insert(new ChatHistory()
                    .setSessionId(sessionId)
                    .setQuestion(question)
                    .setAnswer(answer)
                    .setSourceDocIds(docIds.isEmpty() ? null : docIds)
                    .setRetrievalScores(scores)
                    .setModelUsed(modelUsed)
                    .setLatencyMs(latencyMs));
        } catch (Exception e) {
            log.warn("Chat history write failed (sessionId={}): {}", sessionId, e.getMessage());
        }
    }
}
