package com.simon.rag.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.rag.comm.enums.IntentTag;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

@Service
public class QueryIntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(QueryIntentClassifier.class);

    private static final Map<IntentTag, List<String>> KEYWORD_MAP = new LinkedHashMap<>() {{
        put(IntentTag.ARCHITECTURE, List.of(
            "system design", "architect", "scalab", "design a", "how would you build",
            "high availability", "distributed", "trade.?off", "technical decision",
            "tech stack", "migrat", "redesign", "microservice", "monolith",
            "capacity", "throughput", "fault toleran", "data model"
        ));
        put(IntentTag.PRODUCTION_ISSUE, List.of(
            "incident", "outage", "on.?call", "production bug", "debug",
            "root cause", "postmortem", "went wrong", "urgent fix",
            "latency spike", "memory leak", "crash", "rollback", "hotfix", "pager"
        ));
        put(IntentTag.LEADERSHIP, List.of(
            "led a team", "lead a team", "without authority", "influence",
            "align", "drove alignment", "cross.?team", "stakeholder",
            "mentor", "grow.*engineer", "team.*culture", "hire",
            "org.*change", "direction.*team", "headcount"
        ));
        put(IntentTag.DELIVERY, List.of(
            "deadline", "shipped", "deliver", "scope", "timeline", "launch",
            "release", "on time", "delay", "milestone", "sprint",
            "cut.*feature", "descope", "crunch", "go.?live"
        ));
        put(IntentTag.TECHNICAL_DEPTH, List.of(
            "algorithm", "optimiz", "performance", "complexity", "implement",
            "deep dive", "bottleneck", "profil", "benchmark", "low level",
            "internals", "under the hood", "concurren", "lock", "cache hit"
        ));
        put(IntentTag.AMBIGUITY, List.of(
            "unclear", "ambiguous", "no requirement", "limited.*resource",
            "uncertain", "unknown", "pivot", "figure out",
            "no.*spec", "no.*doc", "moving.*goal", "no.*direction"
        ));
        put(IntentTag.CONFLICT, List.of(
            "disagree", "conflict", "pushback", "tension", "difficult.*colleague",
            "difficult.*manager", "convince", "resistance", "different opinion",
            "friction", "didn.t agree", "escalat", "not.*aligned"
        ));
        put(IntentTag.GROWTH, List.of(
            "learn", "new.*domain", "transition", "ramp up",
            "outside.*comfort", "feedback.*received", "improve yourself",
            "upskill", "new.*tech", "career.*change", "unfamiliar"
        ));
    }};

    private static final String CLASSIFY_SYSTEM = """
        You classify interview questions into retrieval intents for a software engineer's experience database.

        Available intents (return 1-3 from this exact list only):
          architecture, production_issue, leadership, delivery,
          technical_depth, ambiguity, conflict, growth

        Return ONLY a JSON array of strings. No explanation. No markdown.

        Examples:
        "Tell me about a time you led a team through a major incident"
          → ["leadership","production_issue"]
        "How do you handle disagreement with your manager?"
          → ["conflict"]
        "Design a rate limiter"
          → ["architecture","technical_depth"]
        "Tell me about delivering under a tight deadline with unclear requirements"
          → ["delivery","ambiguity"]
        "Walk me through the most complex system you've built"
          → ["architecture","technical_depth"]
        """;

    private final ChatLanguageModel chatLanguageModel;
    private final ObjectMapper objectMapper;

    @Value("${rag.intent-classifier.llm-fallback:true}")
    private boolean llmFallbackEnabled;

    public QueryIntentClassifier(ChatLanguageModel chatLanguageModel, ObjectMapper objectMapper) {
        this.chatLanguageModel = chatLanguageModel;
        this.objectMapper = objectMapper;
    }

    /**
     * Rule-based first. LLM fallback only when no rules matched.
     * Never throws — returns empty list on failure (retrieval proceeds unfiltered).
     */
    public List<IntentTag> classify(String question) {
        List<IntentTag> ruleResult = classifyByRules(question);
        if (!ruleResult.isEmpty()) {
            log.debug("[IntentClassifier] rule {} → {}", question, ruleResult);
            return ruleResult;
        }
        if (llmFallbackEnabled) {
            List<IntentTag> llmResult = classifyByLlm(question);
            log.debug("[IntentClassifier] llm  {} → {}", question, llmResult);
            return llmResult;
        }
        return List.of();
    }

    private List<IntentTag> classifyByRules(String question) {
        String q = question.toLowerCase();
        List<IntentTag> matched = new ArrayList<>();
        for (Map.Entry<IntentTag, List<String>> entry : KEYWORD_MAP.entrySet()) {
            for (String pattern : entry.getValue()) {
                if (Pattern.compile(pattern).matcher(q).find()) {
                    matched.add(entry.getKey());
                    break;
                }
            }
        }
        return matched;
    }

    @SuppressWarnings("unchecked")
    private List<IntentTag> classifyByLlm(String question) {
        try {
            String raw = chatLanguageModel.generate(List.of(
                    SystemMessage.from(CLASSIFY_SYSTEM),
                    UserMessage.from(question)
            )).content().text().strip();

            List<String> tags = objectMapper.readValue(raw, List.class);
            return tags.stream()
                    .map(s -> { try { return IntentTag.fromValue(s); } catch (Exception e) { return null; } })
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.warn("[IntentClassifier] LLM fallback failed: {}", e.getMessage());
            return List.of();
        }
    }
}
