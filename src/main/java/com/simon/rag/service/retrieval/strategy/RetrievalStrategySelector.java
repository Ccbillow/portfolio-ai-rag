package com.simon.rag.service.retrieval.strategy;

import com.simon.rag.service.retrieval.QuerySignalClassifier;
import com.simon.rag.service.retrieval.RetrievalContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Maps a {@link RetrievalContext} to its {@link RetrievalStrategy}.
 * Multi-company detection takes priority over QuestionType; FACTUAL splits into
 * no-rerank (temporal/profile) vs experience by query signals.
 */
@Component
@RequiredArgsConstructor
public class RetrievalStrategySelector {

    private final QuerySignalClassifier signals;
    private final BehavioralRetrievalStrategy behavioral;
    private final FactualExperienceRetrievalStrategy factualExperience;
    private final FactualNoRerankRetrievalStrategy factualNoRerank;
    private final MultiCompanyRetrievalStrategy multiCompany;

    public RetrievalStrategy select(RetrievalContext ctx) {
        if (signals.isMultiCompany(ctx.question(), ctx.focusCompany())) {
            return multiCompany;
        }
        return switch (ctx.questionType()) {
            case BEHAVIORAL, TECHNICAL, STRATEGIC -> behavioral;
            case FACTUAL -> (signals.isTemporal(ctx.question()) || signals.isProfile(ctx.question()))
                    ? factualNoRerank : factualExperience;
            default -> behavioral;
        };
    }
}
