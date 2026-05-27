package com.simon.rag.model.eval;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record EvalCase(
        String id,
        String section,
        String type,
        @JsonProperty("focus_company") String focusCompany,
        String question,
        @JsonProperty("setup_turns") List<String> setupTurns,
        @JsonProperty("must_contain") List<String> mustContain,
        @JsonProperty("must_not_contain") List<String> mustNotContain,
        @JsonProperty("max_words") Integer maxWords,
        @JsonProperty("check_hard_stop") boolean checkHardStop,
        @JsonProperty("manual_hint") String manualHint
) {}
