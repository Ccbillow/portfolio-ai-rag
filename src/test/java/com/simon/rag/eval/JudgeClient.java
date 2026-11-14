package com.simon.rag.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class JudgeClient {

    private static final ObjectMapper M = new ObjectMapper();

    private final HttpClient http;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final double temperature;
    private final int timeoutSeconds;

    public JudgeClient(String apiKey, String baseUrl, String model, double temperature, int timeoutSeconds) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.temperature = temperature;
        this.timeoutSeconds = timeoutSeconds;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public record Verdict(int faithA, int faithB, int complA, int complB,
                          int toneA, int toneB, String verdict, String reason) {}

    public Verdict judge(String question, String hint,
                         List<String> mustContain, List<String> mustNotContain,
                         String answerA, String answerB) throws Exception {

        String prompt = """
            You are a strict evaluator for a Q&A bot answering interview questions about
            Simon Cheng's work experience.

            Question:         %s
            Reference hint:   %s
            Must contain:     %s
            Must NOT contain: %s

            ANSWER A (baseline):
            %s

            ANSWER B (latest):
            %s

            Score each answer 0-5 on three axes:
              FAITHFULNESS — facts match the hint; no hallucinations
              COMPLETENESS — covers required points without padding
              TONE         — interview-ready; no over-humility / lesson-learned tail

            Then emit ONE verdict:
              PASS    — B is equivalent or better than A
              REGRESS — B is meaningfully worse than A on any axis (explain which)
              IMPROVE — B is clearly better than A

            Reply STRICT JSON:
            {
              "faithfulness": {"a": int, "b": int},
              "completeness": {"a": int, "b": int},
              "tone":         {"a": int, "b": int},
              "verdict":      "PASS|REGRESS|IMPROVE",
              "reason":       "<= 40 words"
            }
            """.formatted(question, hint == null ? "" : hint,
                    mustContain, mustNotContain, answerA, answerB);

        JsonNode v = chatJson(prompt);

        return new Verdict(
                v.path("faithfulness").path("a").asInt(),
                v.path("faithfulness").path("b").asInt(),
                v.path("completeness").path("a").asInt(),
                v.path("completeness").path("b").asInt(),
                v.path("tone").path("a").asInt(),
                v.path("tone").path("b").asInt(),
                v.path("verdict").asText("PASS"),
                v.path("reason").asText(""));
    }

    public record Score(int groundedness, int completeness, int tone,
                        List<String> unsupported, String reason) {}

    /**
     * Absolute scoring of one answer. Groundedness is judged against the retrieved context
     * (what the bot was allowed to use), completeness against the reference hint.
     */
    public Score score(String question, String context, String hint,
                       List<String> mustContain, String answer) throws Exception {

        String prompt = """
            You are a strict evaluator for a Q&A bot that answers interview questions in first
            person about one engineer's work experience. The bot may only use facts from the
            Context it was given.

            Question:        %s
            Reference hint:  %s
            Answer keywords: %s

            CONTEXT GIVEN TO THE BOT:
            %s

            ANSWER:
            %s

            Score the answer 0-5 on three axes:
              GROUNDEDNESS — every factual claim (company, project, role, technology, number,
                             outcome) is supported by the Context. 5 = all supported;
                             0 = mostly invented. Ignore the hint for this axis.
              COMPLETENESS — covers what the hint and keywords require, given what the Context
                             makes available. Do not reward padding.
              TONE         — reads like a person answering in an interview: natural and direct,
                             not a list of clipped fragments, no lesson-learned tail.

            List every claim in the answer that the Context does not support.

            Reply STRICT JSON:
            {
              "groundedness": int,
              "completeness": int,
              "tone":         int,
              "unsupported":  ["<claim>", ...],
              "reason":       "<= 40 words"
            }
            """.formatted(question, hint == null ? "" : hint, mustContain, context, answer);

        JsonNode v = chatJson(prompt);
        List<String> unsupported = new java.util.ArrayList<>();
        v.path("unsupported").forEach(n -> unsupported.add(n.asText()));
        return new Score(v.path("groundedness").asInt(), v.path("completeness").asInt(),
                v.path("tone").asInt(), unsupported, v.path("reason").asText(""));
    }

    /**
     * Gold pre-labelling: which of the candidate chunks does a correct answer need?
     * Returns candidate keys; a human reviews the result before it is trusted.
     */
    public List<String> selectGold(String question, String hint, List<String> mustContain,
                                   Map<String, String> candidates) throws Exception {
        StringBuilder chunks = new StringBuilder();
        candidates.forEach((k, text) -> chunks.append("[").append(k).append("]\n").append(text).append("\n\n"));

        String prompt = """
            You label retrieval ground truth for a Q&A bot that answers interview questions
            about one engineer's work experience.

            Question:        %s
            Reference hint:  %s
            Answer keywords: %s

            Candidate chunks, each headed by its [key]:

            %s
            Select the chunks that contain the facts a correct answer must be built from.
            Be strict: pick the smallest sufficient set (usually 1-3). A chunk that is merely
            on the same topic, or duplicates a better chunk, is NOT gold. If no chunk
            contains the needed facts, return an empty list.

            Reply STRICT JSON: {"gold": ["<key>", ...], "reason": "<= 25 words"}
            """.formatted(question, hint == null ? "" : hint, mustContain, chunks);

        JsonNode v = chatJson(prompt);
        List<String> gold = new java.util.ArrayList<>();
        v.path("gold").forEach(n -> { if (candidates.containsKey(n.asText())) gold.add(n.asText()); });
        return gold;
    }

    /** One user message in, one strict-JSON object out. */
    private JsonNode chatJson(String prompt) throws Exception {
        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", temperature,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(Map.of("role", "user", "content", prompt)));

        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(M.writeValueAsString(body)))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("DeepSeek HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode root = M.readTree(resp.body());
        String content = root.path("choices").path(0).path("message").path("content").asText();
        return M.readTree(content);
    }
}
