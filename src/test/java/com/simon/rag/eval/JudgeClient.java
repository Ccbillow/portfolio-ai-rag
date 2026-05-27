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
        JsonNode v = M.readTree(content);

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
}
