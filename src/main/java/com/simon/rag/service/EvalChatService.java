package com.simon.rag.service;

import com.simon.rag.model.eval.EvalCase;
import com.simon.rag.model.eval.EvalResult;

public interface EvalChatService {
    EvalResult evaluate(EvalCase aCase);
}
