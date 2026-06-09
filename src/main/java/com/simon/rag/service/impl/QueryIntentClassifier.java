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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
                "latency spike", "memory leak", "crash", "rollback", "hotfix",
                "serious mistake", "biggest mistake", "major mistake",
                "mistake you made", "what went wrong", "project.*fail",
                "career mistake", "learned from.*fail", "regret",
                "what would you do different", "didn.t go well"
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

            IMPORTANT classification rules:
            — return [] (empty array) for the following question types, do NOT assign any intent:
              - Factual / biographical questions: "how long", "when did you", "what year",
                "how many years", "start date", "end date", "tenure", "notice period",
                "visa status", "where are you from", "salary"
              - These questions need factual chunks, not STAR story chunks.
              
            — "mistake", "failure", "what went wrong", "didn't go well", "regret", "project failed"
                  → ALWAYS include production_issue (these map to incident/debugging stories, not growth)
                - "learn", "growth" alone without mistake context → growth
                - Mistake questions may ALSO include growth if they ask about lessons learned
                      
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
            "Tell me about a time you made a serious mistake"    
              → ["production_issue"]
            "What would you do differently in that project?"     
              → ["production_issue", "growth"]
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

            raw = stripMarkdownFences(raw);
            List<String> tags = objectMapper.readValue(raw, List.class);
            return tags.stream()
                    .map(s -> {
                        try {
                            return IntentTag.fromValue(s);
                        } catch (Exception e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.warn("[IntentClassifier] LLM fallback failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Strips markdown code fences that some Claude models insist on wrapping JSON in.
     */
    private String stripMarkdownFences(String raw) {
        String s = raw.strip();
        if (s.startsWith("```")) {
            int start = s.indexOf('\n');
            if (start < 0) start = 3;
            else start = start + 1;
            int end = s.lastIndexOf("```");
            if (end < 0) end = s.length();
            s = s.substring(start, end).strip();
        }
        return s;
    }
}
