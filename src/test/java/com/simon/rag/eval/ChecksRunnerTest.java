package com.simon.rag.eval;

import com.simon.rag.model.eval.EvalCase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChecksRunnerTest {

    private EvalCase newCase(List<String> must, List<String> mustNot, Integer maxWords, boolean hardStop) {
        return new EvalCase(
                "T1","UnitTest","TECHNICAL",null,"q?", null,
                must, mustNot, maxWords, hardStop, null);
    }

    @Test
    void passes_whenAllSignalsClean() {
        EvalCase c = newCase(List.of("ocbc"), List.of("sanofi"), null, false);
        var r = ChecksRunner.run(c, "OCBC built a kafka pipeline.");
        assertEquals("PASS", r.verdict());
        assertTrue(r.mustContainMissing().isEmpty());
        assertTrue(r.mustNotContainHit().isEmpty());
    }

    @Test
    void fails_onMissingMustContain() {
        EvalCase c = newCase(List.of("ocbc","kafka"), List.of(), null, false);
        var r = ChecksRunner.run(c, "I worked at OCBC.");
        assertEquals("FAIL", r.verdict());
        assertEquals(List.of("kafka"), r.mustContainMissing());
    }

    @Test
    void fails_onMustNotContainHit() {
        EvalCase c = newCase(List.of(), List.of("sanofi"), null, false);
        var r = ChecksRunner.run(c, "OCBC and Sanofi were both clients.");
        assertEquals("FAIL", r.verdict());
        assertEquals(List.of("sanofi"), r.mustNotContainHit());
    }

    @Test
    void detectsHardStop() {
        EvalCase c = newCase(List.of(), List.of(), null, true);
        var r = ChecksRunner.run(c, "I was lead. This taught me discipline.");
        assertEquals("FAIL", r.verdict());
        assertNotNull(r.hardStopHit());
    }

    @Test
    void detectsMaxWordsExceeded() {
        EvalCase c = newCase(List.of(), List.of(), 3, false);
        var r = ChecksRunner.run(c, "one two three four");
        assertTrue(r.maxWordsViolated());
        assertEquals("FAIL", r.verdict());
        assertEquals(4, r.wordCount());
    }

    @Test
    void caseInsensitiveMatching() {
        EvalCase c = newCase(List.of("OCBC"), List.of("Sanofi"), null, false);
        var r = ChecksRunner.run(c, "ocbc and sanofi mentioned");
        assertEquals("FAIL", r.verdict());
        assertEquals(List.of("sanofi"), r.mustNotContainHit());
    }
}
