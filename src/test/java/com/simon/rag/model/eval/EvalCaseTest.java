package com.simon.rag.model.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EvalCaseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deserializesSingleTurnCase() throws Exception {
        String json = """
            {
              "id": "OC1",
              "section": "OCBC",
              "type": "BEHAVIORAL",
              "focus_company": "OCBC",
              "question": "Tell me about OCBC.",
              "setup_turns": null,
              "must_contain": ["ocbc","kafka"],
              "must_not_contain": ["sanofi"],
              "max_words": null,
              "check_hard_stop": false,
              "manual_hint": "should mention sole backend"
            }
            """;
        EvalCase c = mapper.readValue(json, EvalCase.class);
        assertEquals("OC1", c.id());
        assertEquals("BEHAVIORAL", c.type());
        assertEquals("OCBC", c.focusCompany());
        assertNull(c.setupTurns());
        assertEquals(List.of("ocbc","kafka"), c.mustContain());
        assertFalse(c.checkHardStop());
    }

    @Test
    void deserializesMultiTurnCase() throws Exception {
        String json = """
            {
              "id": "MT1", "section": "MULTI_TURN", "type": "MULTI_TURN",
              "focus_company": "OCBC",
              "question": "How did you partition?",
              "setup_turns": ["Tell me about OCBC."],
              "must_contain": ["kafka","partition"],
              "must_not_contain": [],
              "max_words": null,
              "check_hard_stop": false,
              "manual_hint": "follow-up"
            }
            """;
        EvalCase c = mapper.readValue(json, EvalCase.class);
        assertEquals(List.of("Tell me about OCBC."), c.setupTurns());
    }
}
