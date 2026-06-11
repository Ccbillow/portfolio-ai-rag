package com.simon.rag.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.rag.comm.enums.BehavioralTag;
import com.simon.rag.comm.enums.ChunkType;
import com.simon.rag.comm.enums.IntentTag;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;

@Service
public class ChunkMetadataEnricher {

    private static final Logger log = LoggerFactory.getLogger(ChunkMetadataEnricher.class);

    private static final String SYSTEM_PROMPT = """
        You are a metadata extractor for a professional experience knowledge base
        used in software engineering job interviews.

        TAXONOMY:

        intent_tags (pick 1-3, which interview question types this chunk answers) - MUST be chosen ONLY from intent_tags list, no other values allowed:
          architecture      — system design, technical decisions, scalability, trade-offs
          production_issue  — incidents, outages, on-call, debugging under pressure
          leadership        — leading people, influence without authority, cross-team alignment
          delivery          — project execution, deadlines, scope management, shipping
          technical_depth   — algorithms, performance optimization, deep implementation
          ambiguity         — unclear requirements, limited resources, high uncertainty
          conflict          — disagreements, pushback, stakeholder tension
          growth            — reflection, lessons learned, mindset change, personal growth, career development, learning new domains, mentoring others

        behavioral_tags (pick 1-4, what soft skills are demonstrated) - MUST be chosen ONLY from behavioral_tags list, no other values allowed:
          ownership, cross_team_communication, mentoring, data_driven,
          prioritization, stakeholder_management, resilience, proactive

        chunk_type (pick exactly 1):
          incident      — production outage or major bug with resolution
          project       — end-to-end project experience
          decision      — a specific technical or product decision point
          achievement   — quantified outcome, promotion evidence
          process       — team workflow or methodology improvement
          background    — company/team context (low retrieval value)

        Return ONLY valid JSON. No explanation. No markdown fences.
        """;

    private static final String USER_TEMPLATE = """
        Extract metadata from this work experience segment:

        ---
        %s
        ---

        Return JSON:
        {
          "project": "",
          "skills": [],
          "intent_tags": [],
          "behavioral_tags": [],
          "chunk_type": "",
          "confidence": 0.0
        }
        """;

    private final ChatLanguageModel chatLanguageModel;
    private final ObjectMapper objectMapper;
    private final Executor executor;

    @Value("${rag.metadata-enrichment.enabled:true}")
    private boolean enabled;

    @Value("${rag.metadata-enrichment.concurrency:3}")
    private int concurrency;

    @Value("${rag.metadata-enrichment.rate-limit-ms:1500}")
    private long rateLimitMs;

    public ChunkMetadataEnricher(ChatLanguageModel chatLanguageModel,
                                  ObjectMapper objectMapper,
                                  @Qualifier("uploadExecutor") Executor executor) {
        this.chatLanguageModel = chatLanguageModel;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    public List<EnrichmentResult> enrichAll(List<TextSegment> segments) {
        if (!enabled) {
            log.info("[MetadataEnricher] Disabled — skipping enrichment");
            return segments.stream().map(s -> EnrichmentResult.empty()).toList();
        }

        // Submit tasks with external semaphore gating — only concurrency-many tasks
        // ever enter the executor, so pool threads never block waiting for a permit.
        Semaphore semaphore = new Semaphore(concurrency);
        List<CompletableFuture<EnrichmentResult>> futures = new ArrayList<>();

        for (TextSegment segment : segments) {
            try {
                semaphore.acquire();
                // Throttle submissions to stay under Anthropic rate limit (50 RPM for Haiku)
                Thread.sleep(rateLimitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            CompletableFuture<EnrichmentResult> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return enrich(segment.text());
                } finally {
                    semaphore.release();
                }
            }, executor);
            futures.add(future);
        }

        return futures.stream()
                .map(f -> {
                    try { return f.get(30, TimeUnit.SECONDS); }
                    catch (Exception e) {
                        log.warn("[MetadataEnricher] Enrichment timed out or failed: {}", e.getMessage());
                        return EnrichmentResult.empty();
                    }
                })
                .toList();
    }

    private EnrichmentResult enrich(String text) {
        try {
            Response<AiMessage> response = chatLanguageModel.generate(List.of(
                    SystemMessage.from(SYSTEM_PROMPT),
                    UserMessage.from(USER_TEMPLATE.formatted(trimText(text)))
            ));

            String raw = stripMarkdownFences(response.content().text().strip());
            @SuppressWarnings("unchecked")
            Map<String, Object> json = objectMapper.readValue(raw, Map.class);

            return EnrichmentResult.builder()
                    .project(stringOrEmpty(json, "project"))
                    .skills(listOrEmpty(json, "skills"))
                    .intentTags(parseIntentTags(json))
                    .behavioralTags(parseBehavioralTags(json))
                    .chunkType(parseChunkType(json))
                    .confidence(doubleOrZero(json, "confidence"))
                    .build();

        } catch (Exception e) {
            log.warn("[MetadataEnricher] Parse failed: {}", e.getMessage());
            return EnrichmentResult.empty();
        }
    }

    // ── Parsers ─────────────────────────────────────────────────────────────

    private List<IntentTag> parseIntentTags(Map<String, Object> json) {
        return listOrEmpty(json, "intent_tags").stream()
                .map(s -> { try { return IntentTag.fromValue(s); } catch (Exception e) { return null; } })
                .filter(Objects::nonNull)
                .toList();
    }

    private List<BehavioralTag> parseBehavioralTags(Map<String, Object> json) {
        return listOrEmpty(json, "behavioral_tags").stream()
                .map(s -> { try { return BehavioralTag.fromValue(s); } catch (Exception e) { return null; } })
                .filter(Objects::nonNull)
                .toList();
    }

    private ChunkType parseChunkType(Map<String, Object> json) {
        try { return ChunkType.fromValue(stringOrEmpty(json, "chunk_type")); }
        catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private List<String> listOrEmpty(Map<String, Object> json, String key) {
        Object val = json.get(key);
        if (val instanceof List<?> l) return l.stream().map(Object::toString).toList();
        return List.of();
    }

    private String stringOrEmpty(Map<String, Object> json, String key) {
        Object val = json.get(key);
        return val != null ? val.toString() : "";
    }

    private double doubleOrZero(Map<String, Object> json, String key) {
        Object val = json.get(key);
        if (val instanceof Number n) return n.doubleValue();
        return 0.0;
    }

    private String trimText(String text) {
        return text.length() > 2000 ? text.substring(0, 2000) + "…" : text;
    }

    /** Strips markdown code fences that some Claude models insist on wrapping JSON in. */
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

    // ── Result DTO ──────────────────────────────────────────────────────────

    public record EnrichmentResult(
            String project,
            List<String> skills,
            List<IntentTag> intentTags,
            List<BehavioralTag> behavioralTags,
            ChunkType chunkType,
            double confidence
    ) {
        static EnrichmentResult empty() {
            return new EnrichmentResult("", List.of(), List.of(), List.of(), null, 0.0);
        }

        static Builder builder() { return new Builder(); }

        static class Builder {
            private String project = "";
            private List<String> skills = List.of();
            private List<IntentTag> intentTags = List.of();
            private List<BehavioralTag> behavioralTags = List.of();
            private ChunkType chunkType = null;
            private double confidence = 0.0;

            Builder project(String v) { this.project = v; return this; }
            Builder skills(List<String> v) { this.skills = v; return this; }
            Builder intentTags(List<IntentTag> v) { this.intentTags = v; return this; }
            Builder behavioralTags(List<BehavioralTag> v) { this.behavioralTags = v; return this; }
            Builder chunkType(ChunkType v) { this.chunkType = v; return this; }
            Builder confidence(double v) { this.confidence = v; return this; }
            EnrichmentResult build() {
                return new EnrichmentResult(project, skills, intentTags, behavioralTags, chunkType, confidence);
            }
        }
    }
}
