package com.simon.rag.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.rag.model.eval.EvalCase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Generation metrics: the judge scores every answered case in {@code latest.json} for
 * groundedness (vs the retrieved context), completeness (vs the hint) and tone, and
 * writes {@code generation-report.md}. Unlike {@link EvalJudgeTest} this is absolute
 * scoring of all cases, not an A/B verdict on diff candidates.
 * Run: {@code make eval-run} then {@code make eval-generation}.
 */
@Tag("eval-generation")
class GenerationEvalTest {

    private static final int LOW = 3;

    record Scored(String id, String type, JudgeClient.Score score) {}

    @Test
    void scoreAll() throws Exception {
        Path latest = ReportWriter.reportsDir().resolve("latest.json");
        if (!Files.exists(latest)) fail("latest.json missing — run `make eval-run` first");
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) fail("DEEPSEEK_API_KEY not set");
        JudgeClient judge = new JudgeClient(apiKey, "https://api.deepseek.com", "deepseek-chat", 0.0, 60);

        Map<String, EvalCase> byId = new HashMap<>();
        CaseLoader.load().forEach(c -> byId.put(c.id(), c));

        JsonNode root = new ObjectMapper().readTree(latest.toFile());
        List<Scored> scored = new ArrayList<>();
        int skipped = 0;
        for (JsonNode n : root.path("cases")) {
            String id = n.path("id").asText();
            String context = n.path("context").asText("");
            EvalCase c = byId.get(id);
            // Gate replies and no-hit replies never reach the LLM — nothing to ground.
            if (c == null || context.isBlank()) { skipped++; continue; }

            JudgeClient.Score s = judge.score(c.question(), context, c.manualHint(),
                    c.mustContain(), n.path("answer").asText(""));
            scored.add(new Scored(id, c.type(), s));
            System.out.printf("%-5s %-11s grounded=%d complete=%d tone=%d%n",
                    id, c.type(), s.groundedness(), s.completeness(), s.tone());
        }
        if (scored.isEmpty()) fail("No case in latest.json carries a context — re-run `make eval-run`");

        StringBuilder md = new StringBuilder("# Generation Report\n\n")
                .append("run=").append(root.path("runId").asText()).append("  model=")
                .append(root.path("model").asText()).append("  judged=").append(scored.size())
                .append("  skipped (no LLM call)=").append(skipped).append("\n\n")
                .append("Scores are 0-5, judged by deepseek-chat.\n\n")
                .append("| Type | n | Groundedness | Completeness | Tone |\n|---|---|---|---|---|\n");
        scored.stream().collect(Collectors.groupingBy(Scored::type, TreeMap::new, Collectors.toList()))
                .forEach((type, rows) -> md.append(row(type, rows)));
        md.append(row("**ALL**", scored));

        md.append("\n## Low scores (any axis ≤ ").append(LOW).append(")\n\n");
        for (Scored s : scored) {
            JudgeClient.Score v = s.score();
            if (Math.min(v.groundedness(), Math.min(v.completeness(), v.tone())) > LOW) continue;
            md.append("#### ").append(s.id()).append("  ").append(s.type())
                    .append(String.format("  grounded=%d complete=%d tone=%d%n",
                            v.groundedness(), v.completeness(), v.tone()))
                    .append("- ").append(v.reason()).append('\n');
            v.unsupported().forEach(u -> md.append("- unsupported: ").append(u).append('\n'));
            md.append('\n');
        }

        ReportWriter.writeText(md.toString(), ReportWriter.reportsDir().resolve("generation-report.md"));
        System.out.println("GENERATION EVAL DONE → generation-report.md");
    }

    private static String row(String label, List<Scored> rows) {
        return String.format("| %s | %d | %.2f | %.2f | %.2f |%n", label, rows.size(),
                mean(rows, s -> s.score().groundedness()),
                mean(rows, s -> s.score().completeness()),
                mean(rows, s -> s.score().tone()));
    }

    private static double mean(List<Scored> rows, ToIntFunction<Scored> f) {
        return rows.stream().mapToInt(f).average().orElse(0);
    }
}
