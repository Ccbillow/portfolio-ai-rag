package com.simon.rag.service.impl;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rule-based question classifier — zero LLM cost.
 *
 * Priority order (first match wins):
 *   INVALID → OUT_OF_SCOPE → STRATEGIC → FACTUAL → TECHNICAL → BEHAVIORAL
 */
@Component
public class QuestionClassifier {

    private static final Set<String> BARE_INTERROGATIVES =
            Set.of("who", "what", "when", "where", "why", "how",
                    "which", "whose", "huh", "ok", "hi", "hello", "hey");

    private static final Pattern OUT_OF_SCOPE_PATTERN = Pattern.compile(
            "(?i)\\b(weather|forecast|recipe|cook|bake|sport|football|cricket|" +
            "basketball|movie|film|song|music|lyrics|news|politics|president|" +
            "prime minister|capital of|translate|joke|poem|write me a|" +
            "calculate|solve this|what is \\d|stock price|bitcoin|crypto|" +
            "restaurant|hotel|flight|visa(?! status| pr| permanent))\\b");

    private static final Pattern STRATEGIC_PATTERN = Pattern.compile(
            "(?i)\\b(salary|salaries|compensation|package|pay rate|ctc|" +
            "how much (do you|are you|would you|did you)|" +
            "expected (salary|pay|compensation|rate)|" +
            "what (are|were) you (making|earning|paid)|" +
            "weakness|weaknesses|not (good|great|strong) at|" +
            "area.*to improve|room.*to improve|biggest flaw|" +
            "conflict with (?:my |your |the )?(?:manager|boss|supervisor|director|lead)|" +
            "argue with|disagree.*(?:manager|colleague|team)|" +
            "difficult.*(?:manager|colleague|coworker)|" +
            "why should we (?:hire|choose) you over|what makes you better than)\\b");

    private static final Pattern FACTUAL_PATTERN = Pattern.compile(
            "(?i)^(how long|how many|how old|how far|" +
            "when did|when were|when (are|is)|" +
            "where (are|do|did|is|were) you|" +
            "what (year|date|city|country|company|role|title|team)|" +
            "which (company|role|city|team)|" +
            "do you (have|hold|currently)|" +
            "are you (based|located|in australia|in sydney|a pr|an? |currently)|" +
            "have you (lived|worked|studied|graduated)|" +
            "did you (graduate|study|move|relocate|immigrate))");

    private static final Pattern TECHNICAL_PATTERN = Pattern.compile(
            "(?i)\\b(architect(?:ure)?|design pattern|how does .{1,30} work|" +
            "explain (?:the |your )?(?:system|design|approach|implementation)|" +
            "how did you (?:build|solve|handle|fix|deal with|optimize|debug|diagnose|implement)|" +
            "oom|out.?of.?memory|heap dump|full gc|memory leak|jvm|" +
            "concurren(?:t|cy)|thread(?:ing)?|deadlock|race condition|lock|" +
            "performance|throughput|latency|qps|tps|benchmark|load test|" +
            "redis|kafka|qdrant|vector|embedding|rag|rerank|" +
            "spring (?:boot|cloud|security)|microservice|" +
            "docker|kubernetes|k8s|ci.?cd|pipeline|deploy|" +
            "sql|database|index|query|hibernate|mybatis|orm|" +
            "algorithm|complexity|big.?o|refactor|code review|" +
            "api design|rest|grpc|event.driven|message queue)\\b");

    // ── classify ──────────────────────────────────────────────────────

    public QuestionType classify(String question) {
        if (question == null || question.isBlank()) return QuestionType.INVALID;

        String trimmed = question.strip();
        String lower   = trimmed.toLowerCase().replaceAll("[?!.,]+$", "").strip();
        String[] words = lower.split("\\s+");

        // 1. INVALID — too short or bare interrogative
        if (words.length <= 1) return QuestionType.INVALID;
        if (words.length == 2 && BARE_INTERROGATIVES.contains(words[0]))
            return QuestionType.INVALID;

        // 2. OUT_OF_SCOPE
        if (OUT_OF_SCOPE_PATTERN.matcher(trimmed).find())
            return QuestionType.OUT_OF_SCOPE;

        // 3. STRATEGIC
        if (STRATEGIC_PATTERN.matcher(trimmed).find())
            return QuestionType.STRATEGIC;

        // 4. FACTUAL
        if (FACTUAL_PATTERN.matcher(trimmed).find())
            return QuestionType.FACTUAL;

        // 5. TECHNICAL
        if (TECHNICAL_PATTERN.matcher(trimmed).find())
            return QuestionType.TECHNICAL;

        // 6. BEHAVIORAL (default)
        return QuestionType.BEHAVIORAL;
    }

    // ── early-return messages (no RAG cost) ──────────────────────────

    public String earlyReturnMessage(QuestionType type, String question) {
        return switch (type) {
            case INVALID      -> invalidMessage(question);
            case OUT_OF_SCOPE ->
                "That's outside what I can help with here. " +
                "Feel free to ask about my engineering background, projects, or technical experience.";
            case STRATEGIC    ->
                "That's a great topic to explore together. " +
                "I'd prefer to discuss specifics in our conversation — happy to align on expectations then.";
            default -> null; // null = continue to full pipeline
        };
    }

    private String invalidMessage(String question) {
        String lower = question.strip().toLowerCase().replaceAll("[?!.,]+$", "");
        return switch (lower) {
            case "who"  -> "Who are you asking about? Feel free to ask about my background or experience.";
            case "what" -> "Could you be more specific? What would you like to know?";
            case "why"  -> "Why about what? I'm happy to explain my decisions — just give me more context.";
            case "how"  -> "Could you rephrase? For example: 'How did you handle X?' or 'How long did you work at Y?'";
            default     -> "Could you rephrase that? I want to make sure I answer the right thing.";
        };
    }
}
