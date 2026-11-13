package com.simon.rag.service.retrieval.strategy;

import com.simon.rag.comm.enums.QuestionType;
import com.simon.rag.config.RagProperties;
import com.simon.rag.service.retrieval.QuerySignalClassifier;
import com.simon.rag.service.retrieval.RetrievalContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

class RetrievalStrategySelectorTest {

    private final BehavioralRetrievalStrategy behavioral = mock(BehavioralRetrievalStrategy.class);
    private final FactualExperienceRetrievalStrategy factualExperience = mock(FactualExperienceRetrievalStrategy.class);
    private final FactualNoRerankRetrievalStrategy factualNoRerank = mock(FactualNoRerankRetrievalStrategy.class);
    private final MultiCompanyRetrievalStrategy multiCompany = mock(MultiCompanyRetrievalStrategy.class);

    // QuerySignalClassifier is pure (no dependencies) — use the real one.
    private final RetrievalStrategySelector selector = new RetrievalStrategySelector(
            new QuerySignalClassifier(), behavioral, factualExperience, factualNoRerank, multiCompany);

    private static RetrievalContext ctx(QuestionType type, String question, String focusCompany) {
        return new RetrievalContext(type, question, question, List.of(question),
                focusCompany, List.of(), new RagProperties.Embedding());
    }

    @Test
    void multiCompanySignal_withoutFocusCompany_winsOverQuestionType() {
        assertSame(multiCompany, selector.select(
                ctx(QuestionType.BEHAVIORAL, "Walk me through your career", null)));
        assertSame(multiCompany, selector.select(
                ctx(QuestionType.FACTUAL, "What did you do at each company", null)));
    }

    @Test
    void multiCompanySignal_withFocusCompany_isIgnored() {
        assertSame(behavioral, selector.select(
                ctx(QuestionType.BEHAVIORAL, "Walk me through your career at Alipay", "Alipay")));
    }

    @Test
    void behavioralTechnicalStrategic_routeToBehavioral() {
        assertSame(behavioral, selector.select(ctx(QuestionType.BEHAVIORAL, "Tell me about a conflict", null)));
        assertSame(behavioral, selector.select(ctx(QuestionType.TECHNICAL, "How did you fix the OOM", null)));
        assertSame(behavioral, selector.select(ctx(QuestionType.STRATEGIC, "What is your biggest flaw", null)));
    }

    @Test
    void factualTemporal_routesToNoRerank() {
        assertSame(factualNoRerank, selector.select(
                ctx(QuestionType.FACTUAL, "How long did you work at OCBC", "OCBC")));
    }

    @Test
    void factualProfile_routesToNoRerank() {
        assertSame(factualNoRerank, selector.select(
                ctx(QuestionType.FACTUAL, "Do you have a visa", null)));
    }

    @Test
    void otherFactual_routesToFactualExperience() {
        assertSame(factualExperience, selector.select(
                ctx(QuestionType.FACTUAL, "What was your role at OCBC", "OCBC")));
    }

    @Test
    void gateTypes_fallBackToBehavioral() {
        assertSame(behavioral, selector.select(ctx(QuestionType.OUT_OF_SCOPE, "anything else", null)));
    }
}
