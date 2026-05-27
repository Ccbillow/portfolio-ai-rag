package com.simon.rag.model.eval;

import java.util.List;

public record EvalResult(
        String caseId,
        String question,
        String answer,
        String questionType,
        String focusCompanyResolved,
        List<HitDetail> hits,
        TokenStat tokens,
        StageLatency latency,
        String error
) {
    public record HitDetail(
            String chunkId,
            String docId,
            String company,
            double denseScore,
            double rerankScore,
            int rank
    ) {}

    public record TokenStat(int input, int output, int total) {}

    public record StageLatency(long total, long retrieve, long rerank, long llm) {}
}
