package com.simon.rag.service.retrieval.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.rag.comm.enums.QuestionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class QuestionClassifierTest {

    private final QuestionClassifier classifier = new QuestionClassifier();

    /** Every case of the locked classifier eval set — rule classification only, no LLM. */
    static Stream<Arguments> evalSetCases() throws Exception {
        try (InputStream in = QuestionClassifierTest.class.getResourceAsStream("/classifier-eval-set.json")) {
            List<Arguments> args = new ArrayList<>();
            for (JsonNode c : new ObjectMapper().readTree(in)) {
                args.add(Arguments.of(c.get("id").asText(), c.get("question").asText(),
                        QuestionType.valueOf(c.get("expectedType").asText())));
            }
            return args.stream();
        }
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("evalSetCases")
    void evalSet_classification(String id, String question, QuestionType expected) {
        assertEquals(expected, classifier.classify(question));
    }

    @ParameterizedTest(name = "{1} <- {0}")
    @CsvSource(delimiter = '|', value = {
            "hi                                            | INVALID",
            "what?                                         | INVALID",
            "hello there                                   | INVALID",
            "What is the weather like today                | OUT_OF_SCOPE",
            "What is your expected salary                  | STRATEGIC",
            "What is your phone number                     | CONTACT_REDIRECT",
            "Are you married                               | PERSONAL",
            "How long did you work at Alipay               | FACTUAL",
            "How did you design the Kafka architecture     | TECHNICAL",
            "Describe your teamwork                        | BEHAVIORAL"
    })
    void oneCasePerType(String question, QuestionType expected) {
        assertEquals(expected, classifier.classify(question));
    }

    @Test
    void nullOrBlank_isInvalid() {
        assertEquals(QuestionType.INVALID, classifier.classify(null));
        assertEquals(QuestionType.INVALID, classifier.classify("   "));
    }

    @Test
    void priority_tellMeAboutATime_beatsTechnicalKeywords() {
        assertEquals(QuestionType.BEHAVIORAL,
                classifier.classify("Tell me about a time you fixed a memory leak"));
    }

    @Test
    void earlyReturnMessage_onlyForGateTypes() {
        assertNotNull(classifier.earlyReturnMessage(QuestionType.OUT_OF_SCOPE, "weather"));
        assertNotNull(classifier.earlyReturnMessage(QuestionType.STRATEGIC, "salary"));
        assertNull(classifier.earlyReturnMessage(QuestionType.FACTUAL, "How long at Alipay"));
        assertNull(classifier.earlyReturnMessage(QuestionType.TECHNICAL, "How did you fix it"));
        assertNull(classifier.earlyReturnMessage(QuestionType.BEHAVIORAL, "Describe your teamwork"));
    }
}
