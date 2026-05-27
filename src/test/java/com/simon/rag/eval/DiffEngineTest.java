package com.simon.rag.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiffEngineTest {

    private DiffEngine.CaseSnapshot snap(String verdict, List<String> mcMiss, List<String> mncHit,
                                         String answer, int wordCount, String topChunkId) {
        return new DiffEngine.CaseSnapshot(verdict, mcMiss, mncHit, null, wordCount, false,
                answer, topChunkId);
    }

    @Test
    void noChange_returnsNullSignal() {
        var base = snap("PASS", List.of(), List.of(), "same answer", 2, "c1");
        var lat  = snap("PASS", List.of(), List.of(), "same answer", 2, "c1");
        assertNull(DiffEngine.classify(base, lat));
    }

    @Test
    void passToFail_isSeverity5() {
        var base = snap("PASS", List.of(), List.of(), "good", 1, "c1");
        var lat  = snap("FAIL", List.of("kafka"), List.of(), "bad", 1, "c1");
        var sig = DiffEngine.classify(base, lat);
        assertNotNull(sig);
        assertEquals(5, sig.severity());
        assertEquals("regression", sig.label());
    }

    @Test
    void mustNotContainNewHit_isSeverity5() {
        var base = snap("PASS", List.of(), List.of(), "ocbc", 1, "c1");
        var lat  = snap("PASS", List.of(), List.of("sanofi"), "ocbc and sanofi", 3, "c1");
        var sig = DiffEngine.classify(base, lat);
        assertEquals(5, sig.severity());
    }

    @Test
    void missingMustContainAppears_isSeverity4() {
        var base = snap("PASS", List.of(), List.of(), "kafka pipeline", 2, "c1");
        var lat  = snap("PASS", List.of("kafka"), List.of(), "queue based", 2, "c1");
        var sig = DiffEngine.classify(base, lat);
        assertEquals(4, sig.severity());
    }

    @Test
    void wordCountDriftOver30Percent_isSeverity2() {
        var base = snap("PASS", List.of(), List.of(), "same", 100, "c1");
        var lat  = snap("PASS", List.of(), List.of(), "same", 65, "c1");
        var sig = DiffEngine.classify(base, lat);
        assertEquals(2, sig.severity());
        assertEquals("length_drift", sig.label());
    }

    @Test
    void textDrift_lowJaccard_isSeverity1() {
        var base = snap("PASS", List.of(), List.of(), "alpha beta gamma delta", 4, "c1");
        var lat  = snap("PASS", List.of(), List.of(), "epsilon zeta eta theta", 4, "c1");
        var sig = DiffEngine.classify(base, lat);
        assertEquals(1, sig.severity());
        assertEquals("text_drift", sig.label());
    }

    @Test
    void retrievalDrift_topChunkChangedAndAnswerChanged_isSeverity3() {
        var base = snap("PASS", List.of(), List.of(), "alpha beta gamma", 3, "doc1#0");
        var lat  = snap("PASS", List.of(), List.of(), "delta epsilon zeta", 3, "doc2#0");
        var sig = DiffEngine.classify(base, lat);
        assertEquals(3, sig.severity());
        assertEquals("retrieval_drift", sig.label());
    }
}
