package com.simon.rag.service.impl;

public enum QuestionType {
    INVALID,      // fragment / single word — early return, zero cost
    OUT_OF_SCOPE, // clearly unrelated — early return, zero cost
    STRATEGIC,    // salary / weakness / conflict — early return, zero cost
    FACTUAL,      // when / where / how long — full pipeline, 1-sentence hint
    TECHNICAL,    // architecture / code / debug — full pipeline, 3-sentence hint
    BEHAVIORAL    // tell me about / STAR — full pipeline, default hint
}
