package com.simon.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.rag.dao.ChatHistoryMapper;
import com.simon.rag.domain.entity.ChatHistory;
import com.simon.rag.service.retrieval.RerankedHit;
import com.simon.rag.service.vectorstore.QdrantSearchService.SearchHit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatHistoryRecorderTest {

    private final ChatHistoryMapper mapper = mock(ChatHistoryMapper.class);
    private final ChatHistoryRecorder recorder = new ChatHistoryRecorder(mapper, new ObjectMapper());

    private static RerankedHit hit(String docId, int chunkIndex, double rerankScore) {
        return new RerankedHit(
                new SearchHit(0.5, "t", "f.md", "cat", docId, chunkIndex, List.of()), rerankScore);
    }

    @Test
    void record_mapsAllFields_distinctDocIds_scoresAsJson() {
        recorder.record("s-1", "question?", "answer.",
                List.of(hit("7", 0, 0.91), hit("7", 1, 0.5), hit("9", 0, 0.25)),
                "claude-haiku-4-5-20251001", 1234L);

        ArgumentCaptor<ChatHistory> row = ArgumentCaptor.forClass(ChatHistory.class);
        verify(mapper).insert(row.capture());
        assertEquals("s-1", row.getValue().getSessionId());
        assertEquals("question?", row.getValue().getQuestion());
        assertEquals("answer.", row.getValue().getAnswer());
        assertEquals("7,9", row.getValue().getSourceDocIds());
        assertEquals("[0.91,0.5,0.25]", row.getValue().getRetrievalScores());
        assertEquals("claude-haiku-4-5-20251001", row.getValue().getModelUsed());
        assertEquals(1234L, row.getValue().getLatencyMs().longValue());
    }

    @Test
    void record_noHits_leavesSourceFieldsNull() {
        recorder.record("s-1", "q", "a", List.of(), "rule-based", 3L);

        ArgumentCaptor<ChatHistory> row = ArgumentCaptor.forClass(ChatHistory.class);
        verify(mapper).insert(row.capture());
        assertNull(row.getValue().getSourceDocIds());
        assertNull(row.getValue().getRetrievalScores());
    }

    @Test
    void record_blankSessionId_isSkipped() {
        recorder.record(null, "q", "a", List.of(), "rule-based", 3L);
        recorder.record("  ", "q", "a", List.of(), "rule-based", 3L);
        verifyNoInteractions(mapper);
    }

    @Test
    void record_databaseFailure_isSwallowed() {
        when(mapper.insert(any(ChatHistory.class))).thenThrow(new RuntimeException("db down"));
        assertDoesNotThrow(() -> recorder.record("s-1", "q", "a", List.of(), "rule-based", 3L));
    }
}
