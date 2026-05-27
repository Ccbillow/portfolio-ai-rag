package com.simon.rag.eval;

import com.simon.rag.model.eval.EvalCase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CaseLoaderTest {

    @Test
    void loadsAllCasesFromClasspath() {
        List<EvalCase> cases = CaseLoader.load();
        assertEquals(50, cases.size(), "eval-set.json must hold exactly 50 cases");
        assertTrue(cases.stream().allMatch(c -> c.id() != null && c.question() != null));
    }

    @Test
    void multiTurnCasesHaveSetupTurns() {
        List<EvalCase> cases = CaseLoader.load();
        long bad = cases.stream()
                .filter(c -> "MULTI_TURN".equals(c.type()))
                .filter(c -> c.setupTurns() == null || c.setupTurns().isEmpty())
                .count();
        assertEquals(0, bad, "every MULTI_TURN case must have non-empty setup_turns");
    }
}
