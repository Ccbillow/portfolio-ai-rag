package com.simon.rag.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.rag.config.RagProperties;
import com.simon.rag.model.eval.EvalCase;
import com.simon.rag.service.cache.RedisCacheService;
import com.simon.rag.service.retrieval.RetrievalResult;
import com.simon.rag.service.retrieval.RetrievalService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Retrieval-only eval against {@code gold-labels.json} — no answer LLM is called.
 * Runs every case under each ablation config and writes {@code retrieval-report.md}:
 * metrics per strategy, the ablation table, and per-case failure attribution.
 * Run: {@code make eval-retrieval}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("eval-retrieval")
class RetrievalEvalTest {

    private static final int K = 5;

    // Attribution labels, in pipeline order (earliest stage wins when several chunks are lost).
    // ROUTING = the rule gate answered a question that has gold chunks.
    static final String OK = "ok";
    static final String ROUTING = "routing";
    static final String RETRIEVAL_MISS = "retrieval miss";
    static final String RERANK_DROP = "rerank drop";
    static final String COMPANY_FILTER = "company filter";
    static final String GENERATION = "generation";
    private static final List<String> STAGE_ORDER =
            List.of(ROUTING, RETRIEVAL_MISS, COMPANY_FILTER, RERANK_DROP, GENERATION, OK);

    @Autowired private RetrievalService retrievalService;
    @Autowired private RedisCacheService redisCacheService;
    @Autowired private RagProperties ragProperties;

    record Row(String caseId, String strategy, double recallAtK, double recallCtx, double rr,
               boolean hit, String attribution, String detail) {}

    @Test
    void run() throws Exception {
        Map<String, List<String>> gold = RetrievalEvalSupport.loadGold();
        List<EvalCase> cases = CaseLoader.load().stream()
                .filter(c -> !gold.getOrDefault(c.id(), List.of()).isEmpty())
                .toList();
        Map<String, String> answerVerdicts = answerVerdicts();

        // Cohere trial keys allow ~10 rerank calls/min; the default 800 ms gap triggers 429s.
        ragProperties.getReranker().setRateLimitMs(Integer.getInteger("eval.rerank.gap.ms", 6500));

        Map<RetrievalEvalSupport.Config, List<Row>> rows = new LinkedHashMap<>();
        for (RetrievalEvalSupport.Config cfg : RetrievalEvalSupport.ABLATION) {
            List<Row> out = new ArrayList<>();
            for (EvalCase c : cases) {
                RetrievalResult r = RetrievalEvalSupport.retrieveChecked(
                        c, cfg, ragProperties, retrievalService, redisCacheService);
                out.add(score(c, gold.get(c.id()), r, answerVerdicts.get(c.id())));
            }
            rows.put(cfg, out);
            System.out.printf("%-22s %s%n", cfg.name(), metrics(out));
        }

        ReportWriter.writeText(report(rows, cases.size(), !answerVerdicts.isEmpty()),
                ReportWriter.reportsDir().resolve("retrieval-report.md"));
        System.out.println("RETRIEVAL EVAL DONE → retrieval-report.md");
    }

    // ── scoring ──────────────────────────────────────────────────────

    static Row score(EvalCase c, List<String> gold, RetrievalResult r, String answerVerdict) {
        if (r.hasEarlyReturn()) {
            return new Row(c.id(), "(gate)", 0, 0, 0, false, ROUTING,
                    "early return as " + r.questionType());
        }
        List<String> hits = r.hits().stream().map(rh -> RetrievalEvalSupport.key(rh.hit())).toList();
        List<String> topK = hits.subList(0, Math.min(K, hits.size()));

        double recallAtK = gold.stream().filter(topK::contains).count() / (double) gold.size();
        double recallCtx = gold.stream().filter(hits::contains).count() / (double) gold.size();
        int first = java.util.stream.IntStream.range(0, hits.size())
                .filter(i -> gold.contains(hits.get(i))).findFirst().orElse(-1);
        double rr = first < 0 ? 0 : 1.0 / (first + 1);

        // Attribution: where did each gold chunk that never reached the context get lost?
        Set<String> candidates = r.stages().candidates().stream()
                .map(RetrievalEvalSupport::key).collect(Collectors.toSet());
        Set<String> scoped = r.stages().scoped().stream()
                .map(RetrievalEvalSupport::key).collect(Collectors.toSet());
        String expected = expectedType(c);
        boolean misrouted = expected != null && !expected.equals(r.questionType().name());

        Map<String, List<String>> lost = new TreeMap<>(java.util.Comparator.comparingInt(STAGE_ORDER::indexOf));
        for (String g : gold) {
            if (hits.contains(g)) continue;
            String stage = !candidates.contains(g) ? RETRIEVAL_MISS
                    : !scoped.contains(g) ? COMPANY_FILTER
                    : RERANK_DROP;
            lost.computeIfAbsent(stage, k -> new ArrayList<>()).add(g);
        }

        String attribution;
        String detail;
        if (!lost.isEmpty()) {
            attribution = lost.keySet().iterator().next();
            detail = lost.entrySet().stream()
                    .map(e -> e.getKey() + ": " + String.join(", ", e.getValue()))
                    .collect(Collectors.joining("; "));
            // Informational only: TECHNICAL and BEHAVIORAL share a retrieval strategy, so a
            // type mismatch changes the prompt, not which chunks are retrieved.
            if (misrouted) detail += " (type: expected " + expected + ", got " + r.questionType() + ")";
        } else if ("FAIL".equals(answerVerdict)) {
            attribution = GENERATION;
            detail = "all gold chunks in context, answer checks failed";
        } else {
            attribution = OK;
            detail = "";
        }
        return new Row(c.id(), r.strategyName(), recallAtK, recallCtx, rr, recallAtK > 0, attribution, detail);
    }

    /** Eval-set {@code type} doubles as the expected QuestionType only for these three. */
    private static String expectedType(EvalCase c) {
        return Set.of("TECHNICAL", "BEHAVIORAL", "FACTUAL").contains(c.type()) ? c.type() : null;
    }

    /** caseId → PASS/FAIL from the last {@code make eval-run}; empty when it has not been run. */
    private static Map<String, String> answerVerdicts() throws Exception {
        Path latest = ReportWriter.reportsDir().resolve("latest.json");
        Map<String, String> out = new HashMap<>();
        if (!Files.exists(latest)) return out;
        for (JsonNode n : new ObjectMapper().readTree(latest.toFile()).path("cases")) {
            out.put(n.path("id").asText(), n.path("verdict").asText());
        }
        return out;
    }

    // ── report ───────────────────────────────────────────────────────

    private static String metrics(List<Row> rows) {
        return String.format("n=%d Recall@%d=%.2f Recall@ctx=%.2f MRR=%.2f Hit@%d=%.2f",
                rows.size(), K, avg(rows, Row::recallAtK), avg(rows, Row::recallCtx),
                avg(rows, Row::rr), K, avg(rows, r -> r.hit() ? 1.0 : 0.0));
    }

    private static double avg(List<Row> rows, java.util.function.ToDoubleFunction<Row> f) {
        return rows.stream().mapToDouble(f).average().orElse(0);
    }

    private static String metricCells(List<Row> rows) {
        return String.format("%d | %.2f | %.2f | %.2f | %.2f", rows.size(),
                avg(rows, Row::recallAtK), avg(rows, Row::recallCtx),
                avg(rows, Row::rr), avg(rows, r -> r.hit() ? 1.0 : 0.0));
    }

    private static String report(Map<RetrievalEvalSupport.Config, List<Row>> rows, int n, boolean hasAnswers) {
        List<Row> prod = rows.get(RetrievalEvalSupport.FULL);
        String header = "| n | Recall@" + K + " | Recall@ctx | MRR | Hit@" + K + " |\n";
        StringBuilder md = new StringBuilder("# Retrieval Report\n\n")
                .append(n).append(" labelled cases. Recall@ctx = gold chunks anywhere in the final context ")
                .append("(MULTI_COMPANY contexts exceed ").append(K).append(" chunks).\n\n");

        md.append("## Production config, per strategy\n\n| Strategy ").append(header)
                .append("|---|---|---|---|---|---|\n");
        byStrategy(prod).forEach((s, rs) -> md.append("| ").append(s).append(" | ").append(metricCells(rs)).append(" |\n"));
        md.append("| **ALL** | ").append(metricCells(prod)).append(" |\n\n");

        md.append("## Ablation\n\n| Strategy | Config ").append(header).append("|---|---|---|---|---|---|---|\n");
        List<String> strategies = new ArrayList<>(byStrategy(prod).keySet());
        strategies.add("ALL");
        for (String s : strategies) {
            rows.forEach((cfg, rs) -> {
                // group by the production strategy of each case so rows stay comparable across configs
                Set<String> ids = prod.stream().filter(r -> s.equals("ALL") || r.strategy().equals(s))
                        .map(Row::caseId).collect(Collectors.toSet());
                List<Row> sub = rs.stream().filter(r -> ids.contains(r.caseId())).toList();
                md.append("| ").append(s).append(" | ").append(cfg.name()).append(" | ")
                        .append(metricCells(sub)).append(" |\n");
            });
        }

        md.append("\n## Failure attribution (production config)\n\n");
        if (!hasAnswers) md.append("> No latest.json — run `make eval-run` to separate `generation` from `ok`.\n\n");
        md.append("| Attribution | Cases |\n|---|---|\n");
        prod.stream().collect(Collectors.groupingBy(Row::attribution,
                        () -> new TreeMap<>(java.util.Comparator.comparingInt(STAGE_ORDER::indexOf)),
                        Collectors.counting()))
                .forEach((a, cnt) -> md.append("| ").append(a).append(" | ").append(cnt).append(" |\n"));

        md.append("\n| Case | Strategy | Attribution | Detail |\n|---|---|---|---|\n");
        prod.stream().filter(r -> !OK.equals(r.attribution()))
                .forEach(r -> md.append("| ").append(r.caseId()).append(" | ").append(r.strategy())
                        .append(" | ").append(r.attribution()).append(" | ").append(r.detail()).append(" |\n"));
        return md.toString();
    }

    private static Map<String, List<Row>> byStrategy(List<Row> rows) {
        return rows.stream().collect(Collectors.groupingBy(Row::strategy, TreeMap::new, Collectors.toList()));
    }
}
