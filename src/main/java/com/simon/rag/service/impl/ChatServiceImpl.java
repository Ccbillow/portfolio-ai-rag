package com.simon.rag.service.impl;

import com.simon.rag.domain.dto.Dtos;
import com.simon.rag.domain.vo.Vos;
import com.simon.rag.service.ChatService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
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
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ChatLanguageModel chatLanguageModel;

    @Value("${rag.embedding.top-k:3}")
    private int topK;

    @Value("${rag.embedding.min-score:0.5}")
    private double minScore;

    @Override
    public Vos.ChatResponse ask(Dtos.ChatRequest request, Long userId) {
        long start = System.currentTimeMillis();
        log.info("RAG ask: '{}'", request.getQuestion());

        // 1. Embed the question
        Embedding queryEmbedding = embeddingModel.embed(request.getQuestion()).content();

        // 2. Search Qdrant for relevant chunks
        List<EmbeddingMatch<TextSegment>> matches =
                embeddingStore.findRelevant(queryEmbedding, topK, minScore);
        log.info("Qdrant returned {} matches (minScore={})", matches.size(), minScore);

        // 3. No relevant context found — answer without RAG
        if (matches.isEmpty()) {
            return Vos.ChatResponse.builder()
                    .answer("I don't have specific information about that in my knowledge base. "
                            + "Please try rephrasing your question.")
                    .sources(List.of())
                    .modelUsed("claude-3-haiku-20240307")
                    .latencyMs(System.currentTimeMillis() - start)
                    .build();
        }

        // 4. Build context from matched chunks
        String context = matches.stream()
                .map(m -> m.embedded().text())
                .collect(Collectors.joining("\n\n---\n\n"));

        // 5. Build prompt and call Claude
        String prompt = buildPrompt(request.getQuestion(), context);
        String answer = chatLanguageModel.generate(prompt);

        // 6. Build source references for the response
        List<Vos.SourceChunk> sources = matches.stream()
                .map(m -> {
                    String text = m.embedded().text();
                    String fileName = m.embedded().metadata().getString("fileName");
                    String category = m.embedded().metadata().getString("category");
                    return Vos.SourceChunk.builder()
                            .fileName(fileName)
                            .category(category)
                            .score(m.score())
                            .contentPreview(text.substring(0, Math.min(200, text.length())))
                            .build();
                })
                .collect(Collectors.toList());

        return Vos.ChatResponse.builder()
                .answer(answer)
                .sources(sources)
                .modelUsed("claude-3-haiku-20240307")
                .latencyMs(System.currentTimeMillis() - start)
                .build();
    }

    @Override
    public SseEmitter askStream(Dtos.ChatRequest request, Long userId) {
        // TODO: implement SSE streaming with Claude's streaming API
        SseEmitter emitter = new SseEmitter(30_000L);
        try {
            Vos.ChatResponse response = ask(request, userId);
            emitter.send(SseEmitter.event().name("message").data(response.getAnswer()));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    private String buildPrompt(String question, String context) {
        return """
                You are Simon's professional AI assistant. \
                Answer questions about Simon's background, skills, and experience \
                based solely on the provided context.

                Context from Simon's knowledge base:
                """ + context + """


                Question: """ + question + """


                Answer concisely and professionally. \
                If the context doesn't contain enough information, say so honestly.""";
    }
}
