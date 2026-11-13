package com.simon.rag.service.impl;

import com.simon.rag.service.cache.RedisCacheService;
import com.simon.rag.service.prompt.PromptBuilder;

import com.simon.rag.config.RagProperties;
import com.simon.rag.domain.dto.Dtos;
import com.simon.rag.domain.vo.Vos;
import com.simon.rag.service.retrieval.RerankedHit;
import com.simon.rag.service.ChatHistoryRecorder;
import com.simon.rag.service.ChatService;
import com.simon.rag.llm.ClaudeBusyException;
import com.simon.rag.llm.LlmClient;
import com.simon.rag.service.retrieval.RetrievalResult;
import com.simon.rag.service.retrieval.RetrievalService;
import com.simon.rag.service.vectorstore.QdrantSearchService.SearchHit;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
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

    private final LlmClient llmClient;
    private final StreamingChatLanguageModel streamingChatLanguageModel;
    private final RagProperties ragProperties;
    private final PromptBuilder promptBuilder;
    private final RedisCacheService redisCacheService;
    private final RetrievalService retrievalService;
    private final ChatHistoryRecorder chatHistoryRecorder;

    @Value("${langchain4j.anthropic.chat-model-name:claude-haiku-4-5-20251001}")
    private String modelName;

    // ── User-facing copy ────────────────────────────────────────────
    private static final String SESSION_LIMIT_MESSAGE =
            "You've reached the question limit for this session. Please refresh the page to start a new conversation.";
    private static final String NO_HITS_MESSAGE =
            "I don't have specific information about that in my knowledge base. "
                    + "Please try rephrasing your question.";
    private static final String TIMEOUT_MESSAGE =
            "TBot is taking longer than expected. Please try again.";
    private static final String OVERLOADED_MESSAGE =
            "TBot is a bit overloaded right now — please try again in a moment.";
    private static final String GENERIC_ERROR_MESSAGE =
            "Something went wrong — please try again.";

    /** modelUsed marker for non-LLM (gate) responses. */
    private static final String MODEL_RULE_BASED = "rule-based";

    // ----------------------------------------------------------------
    //  Synchronous — Postman / REST clients
    // ----------------------------------------------------------------

    @Override
    public Vos.ChatResponse ask(Dtos.ChatRequest request) {
        long start = System.currentTimeMillis();
        log.info("RAG ask: '{}'", request.getQuestion());

        // 1. Cache check — disabled until production (rag.cache.enabled: false)
        if (ragProperties.getCache().isEnabled()) {
            Vos.ChatResponse cached = redisCacheService.getChatCache(
                    request.getSessionId(), request.getQuestion(), start);
            if (cached != null) return cached;
        }

        // 2. Session limit gate
        if (redisCacheService.isSessionLimitReached(request.getSessionId())) {
            return ruleBasedResponse(SESSION_LIMIT_MESSAGE, start);
        }

        // 3. Retrieval pipeline
        RetrievalResult retrieval = retrievalService.retrieve(request.getQuestion(), request.getSessionId());
        if (retrieval.hasEarlyReturn()) {
            chatHistoryRecorder.record(request.getSessionId(), request.getQuestion(),
                    retrieval.earlyReturn(), List.of(), MODEL_RULE_BASED, System.currentTimeMillis() - start);
            return ruleBasedResponse(retrieval.earlyReturn(), start);
        }
        if (retrieval.noHits()) {
            chatHistoryRecorder.record(request.getSessionId(), request.getQuestion(),
                    NO_HITS_MESSAGE, List.of(), MODEL_RULE_BASED, System.currentTimeMillis() - start);
            return modelResponse(NO_HITS_MESSAGE, List.of(), start);
        }

        // 4. Call Claude
        String answer;
        try {
            String prompt = promptBuilder.build(request.getQuestion(), retrieval.context(),
                    retrieval.questionType(), retrieval.history(), retrieval.focusCompany(), retrieval.strategyName());
            LlmClient.LlmReply reply = llmClient.chat(prompt);
            answer = reply.text();
            logTokenUsage("sync", reply.tokenUsage(), request.getQuestion());
        } catch (Exception e) {
            log.error("Claude generate error", e);
            return modelResponse(errorMessage(e), List.of(), start);
        }
        redisCacheService.appendConversationHistory(
                request.getSessionId(), request.getQuestion(), answer);
        chatHistoryRecorder.record(request.getSessionId(), request.getQuestion(), answer,
                retrieval.hits(), modelName, System.currentTimeMillis() - start);

        Vos.ChatResponse response = modelResponse(answer, toSources(retrieval.hits()), start);

        // 5. Cache store
        if (ragProperties.getCache().isEnabled()) {
            redisCacheService.putChatCache(request.getSessionId(), request.getQuestion(), response);
        }

        return response;
    }

    // ----------------------------------------------------------------
    //  Streaming — SSE for frontend real-time chat UI
    // ----------------------------------------------------------------

    @Override
    public SseEmitter askStream(Dtos.ChatRequest request) {
        long start = System.currentTimeMillis();
        SseEmitter emitter = new SseEmitter(90_000L);
        emitter.onTimeout(() -> {
            try {
                emitter.send(SseEmitter.event().name("error").data(TIMEOUT_MESSAGE));
                emitter.complete();
            } catch (Exception ignored) {
            }
        });
        try {
            // 1. Session limit gate
            if (redisCacheService.isSessionLimitReached(request.getSessionId())) {
                sendMessageAndDone(emitter, SESSION_LIMIT_MESSAGE);
                return emitter;
            }

            // 2. Retrieval pipeline
            RetrievalResult retrieval = retrievalService.retrieve(request.getQuestion(), request.getSessionId());
            if (retrieval.hasEarlyReturn()) {
                sendMessageAndDone(emitter, retrieval.earlyReturn());
                chatHistoryRecorder.record(request.getSessionId(), request.getQuestion(),
                        retrieval.earlyReturn(), List.of(), MODEL_RULE_BASED, System.currentTimeMillis() - start);
                return emitter;
            }
            log.info("Stream — {} hits after retrieval pipeline",
                    retrieval.hits() == null ? 0 : retrieval.hits().size());

            if (retrieval.noHits()) {
                sendMessageAndDone(emitter, NO_HITS_MESSAGE);
                chatHistoryRecorder.record(request.getSessionId(), request.getQuestion(),
                        NO_HITS_MESSAGE, List.of(), MODEL_RULE_BASED, System.currentTimeMillis() - start);
                return emitter;
            }

            emitter.send(SseEmitter.event().name("sources").data(toSources(retrieval.hits())));

            String question = request.getQuestion();
            String sessionId = request.getSessionId();

            // 3. Call Claude (streaming) — shares the interactive throttle via the gateway
            llmClient.throttleInteractive();
            streamingChatLanguageModel.generate(
                    promptBuilder.build(question, retrieval.context(), retrieval.questionType(),
                            retrieval.history(), retrieval.focusCompany(), retrieval.strategyName()),
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
                            logTokenUsage("stream", response.tokenUsage(), question);
                            redisCacheService.appendConversationHistory(
                                    sessionId, question, fullAnswer.toString());
                            chatHistoryRecorder.record(sessionId, question, fullAnswer.toString(),
                                    retrieval.hits(), modelName, System.currentTimeMillis() - start);
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
                                emitter.send(SseEmitter.event().name("error").data(errorMessage(error)));
                                emitter.complete();
                            } catch (Exception ex) {
                                emitter.completeWithError(ex);
                            }
                        }
                    });

        } catch (ClaudeBusyException busy) {
            log.warn("Stream rejected — Claude rate limit saturated");
            try {
                emitter.send(SseEmitter.event().name("error").data(OVERLOADED_MESSAGE));
                emitter.complete();
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            log.error("askStream failed", e);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    // ----------------------------------------------------------------
    //  Response helpers
    // ----------------------------------------------------------------

    private Vos.ChatResponse ruleBasedResponse(String answer, long start) {
        return Vos.ChatResponse.builder()
                .answer(answer)
                .sources(List.of())
                .modelUsed(MODEL_RULE_BASED)
                .latencyMs(System.currentTimeMillis() - start)
                .build();
    }

    private Vos.ChatResponse modelResponse(String answer, List<Vos.SourceChunk> sources, long start) {
        return Vos.ChatResponse.builder()
                .answer(answer)
                .sources(sources)
                .modelUsed(modelName)
                .latencyMs(System.currentTimeMillis() - start)
                .build();
    }

    private void sendMessageAndDone(SseEmitter emitter, String message) throws java.io.IOException {
        emitter.send(SseEmitter.event().name("message").data(message));
        emitter.send(SseEmitter.event().name("done").data("[DONE]"));
        emitter.complete();
    }

    private String errorMessage(Throwable e) {
        return (e instanceof ClaudeBusyException || llmClient.isQuotaError(e))
                ? OVERLOADED_MESSAGE : GENERIC_ERROR_MESSAGE;
    }

    private void logTokenUsage(String tag, TokenUsage usage, String question) {
        if (usage == null) return;
        log.info("TOKEN {} — in={} out={} total={} | q='{}'", tag,
                usage.inputTokenCount(), usage.outputTokenCount(), usage.totalTokenCount(),
                question.length() > 60 ? question.substring(0, 60) + "..." : question);
    }

    private List<Vos.SourceChunk> toSources(List<RerankedHit> hits) {
        return hits.stream()
                .map(rh -> {
                    SearchHit h = rh.hit();
                    Long docId = null;
                    try {
                        if (h.docId() != null) docId = Long.parseLong(h.docId());
                    } catch (NumberFormatException ignored) {
                    }
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
