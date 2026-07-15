package com.simon.rag.service.impl;

import com.simon.rag.service.cache.RedisCacheService;
import com.simon.rag.service.prompt.PromptBuilder;

import com.simon.rag.model.eval.EvalCase;
import com.simon.rag.model.eval.EvalResult;
import com.simon.rag.model.eval.EvalResult.HitDetail;
import com.simon.rag.model.eval.EvalResult.StageLatency;
import com.simon.rag.model.eval.EvalResult.TokenStat;
import com.simon.rag.model.eval.RerankedHit;
import com.simon.rag.service.EvalChatService;
import com.simon.rag.llm.LlmClient;
import com.simon.rag.service.retrieval.RetrievalResult;
import com.simon.rag.service.retrieval.RetrievalService;
import com.simon.rag.service.vectorstore.QdrantSearchService.SearchHit;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvalChatServiceImpl implements EvalChatService {

    private final RetrievalService retrievalService;
    private final LlmClient llmClient;
    private final PromptBuilder promptBuilder;
    private final RedisCacheService redisCacheService;

    @Override
    public EvalResult evaluate(EvalCase aCase) {
        String sessionId = "eval-" + aCase.id() + "-" + UUID.randomUUID();
        long t0 = System.currentTimeMillis();
        try {
            // 1. Replay setup turns into Redis history (multi-turn only)
            if (aCase.setupTurns() != null && !aCase.setupTurns().isEmpty()) {
                for (String prior : aCase.setupTurns()) {
                    redisCacheService.appendConversationHistory(sessionId, prior, "");
                }
            }

            // 2. Retrieval pipeline (shared with production ask/askStream)
            RetrievalResult r = retrievalService.retrieve(aCase.question(), sessionId);
            if (r.hasEarlyReturn()) {
                return new EvalResult(
                        aCase.id(), aCase.question(), r.earlyReturn(),
                        r.questionType().name(), null,
                        List.of(),
                        new TokenStat(0, 0, 0),
                        new StageLatency(System.currentTimeMillis() - t0, r.retrieveMs(), 0, 0),
                        null);
            }
            if (r.noHits()) {
                return new EvalResult(
                        aCase.id(), aCase.question(),
                        "I don't have specific information about that in my knowledge base.",
                        r.questionType().name(), r.focusCompany(),
                        List.of(),
                        new TokenStat(0, 0, 0),
                        new StageLatency(System.currentTimeMillis() - t0, r.retrieveMs(), 0, 0),
                        null);
            }

            // 3. Call Claude — cache deliberately not consulted
            long tLlmStart = System.currentTimeMillis();
            String prompt = promptBuilder.build(aCase.question(), r.context(),
                    r.questionType(), r.history(), r.focusCompany(), r.strategyName());
            LlmClient.LlmReply reply = llmClient.chat(prompt);
            long tLlmEnd = System.currentTimeMillis();

            String answer = reply.text();
            TokenUsage usage = reply.tokenUsage();
            TokenStat tokens = usage != null
                    ? new TokenStat(
                            nz(usage.inputTokenCount()),
                            nz(usage.outputTokenCount()),
                            nz(usage.totalTokenCount()))
                    : new TokenStat(0, 0, 0);

            // 4. Hit details
            List<HitDetail> hitDetails = IntStream.range(0, r.hits().size())
                    .mapToObj(i -> {
                        RerankedHit rh = r.hits().get(i);
                        SearchHit h = rh.hit();
                        String company = (h.companies() == null || h.companies().isEmpty())
                                ? null : h.companies().get(0);
                        return new HitDetail(
                                h.docId() + "#" + h.chunkIndex(),
                                h.docId(), company,
                                h.score(), rh.rerankScore(), i);
                    })
                    .toList();

            return new EvalResult(
                    aCase.id(), aCase.question(), answer,
                    r.questionType().name(), r.focusCompany(),
                    hitDetails,
                    tokens,
                    new StageLatency(
                            System.currentTimeMillis() - t0,
                            r.retrieveMs(),
                            0,
                            tLlmEnd - tLlmStart),
                    null);

        } catch (Exception e) {
            log.error("Eval failed for case {}", aCase.id(), e);
            return new EvalResult(
                    aCase.id(), aCase.question(), null,
                    null, null, List.of(),
                    new TokenStat(0, 0, 0),
                    new StageLatency(System.currentTimeMillis() - t0, 0, 0, 0),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            try {
                redisCacheService.clearSession(sessionId);
            } catch (Exception ignored) {
            }
        }
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
