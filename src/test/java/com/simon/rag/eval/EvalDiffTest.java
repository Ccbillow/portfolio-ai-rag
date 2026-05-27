package com.simon.rag.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.rag.model.eval.EvalCase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.fail;

@Tag("eval-diff")
class EvalDiffTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void diff() throws Exception {
        Path baseline = ReportWriter.reportsDir().resolve("baseline.json");
        Path latest   = ReportWriter.reportsDir().resolve("latest.json");

        if (!Files.exists(baseline)) {
            System.out.println("No baseline.json — run `make promote-baseline` after first eval-run.");
            ReportWriter.writeText(
                    "# Diff Report\n\nNo baseline yet — run promote-baseline.\n",
                    ReportWriter.reportsDir().resolve("diff-report.md"));
            return;
        }
        if (!Files.exists(latest)) {
            fail("latest.json missing — run -Dgroups=eval-run first");
        }

        Map<String, JsonNode> baseCases = indexById(M.readTree(baseline.toFile()).get("cases"));
        Map<String, JsonNode> latCases  = indexById(M.readTree(latest.toFile()).get("cases"));

        // Load eval-set for per-case metadata (type, must_contain, etc.)
        List<EvalCase> evalCases = CaseLoader.load();
        Map<String, EvalCase> evalById = new HashMap<>();
        evalCases.forEach(c -> evalById.put(c.id(), c));

        List<Map<String, Object>> candidates = new ArrayList<>();
        int maxSeverity = 0;

        StringBuilder md = new StringBuilder();
        md.append("# Diff Report\n\n");
        md.append("baseline: ").append(baseline).append('\n');
        md.append("latest:   ").append(latest).append("\n\n");

        List<String> added = latCases.keySet().stream().filter(id -> !baseCases.containsKey(id)).toList();
        List<String> removed = baseCases.keySet().stream().filter(id -> !latCases.containsKey(id)).toList();
        if (!removed.isEmpty()) {
            md.append("## Removed cases (forbidden)\n");
            removed.forEach(id -> md.append("- ").append(id).append('\n'));
            md.append('\n');
            fail("eval-set must not delete cases; removed=" + removed);
        }
        if (!added.isEmpty()) {
            md.append("## Added cases (informational)\n");
            added.forEach(id -> md.append("- ").append(id).append('\n'));
            md.append('\n');
        }

        md.append("## Signals\n\n");

        for (String id : baseCases.keySet()) {
            JsonNode b = baseCases.get(id);
            JsonNode l = latCases.get(id);
            if (l == null) continue;

            var bs = toSnapshot(b);
            var ls = toSnapshot(l);
            DiffEngine.Signal sig = DiffEngine.classify(bs, ls);
            if (sig == null) continue;

            maxSeverity = Math.max(maxSeverity, sig.severity());

            md.append("### ").append(id).append("  severity=").append(sig.severity())
              .append("  ").append(sig.label()).append('\n');
            md.append("- ").append(sig.detail()).append('\n');
            md.append("- baseline: ").append(truncate(bs.answer(), 120)).append('\n');
            md.append("- latest:   ").append(truncate(ls.answer(), 120)).append("\n\n");

            EvalCase evalCase = evalById.get(id);

            Map<String, Object> cand = new LinkedHashMap<>();
            cand.put("caseId", id);
            cand.put("type", evalCase != null ? evalCase.type() : null);
            cand.put("signal", sig.label());
            cand.put("severity", sig.severity());
            cand.put("detail", sig.detail());
            if (evalCase != null) {
                cand.put("must_contain", evalCase.mustContain());
                cand.put("must_not_contain", evalCase.mustNotContain());
                cand.put("manual_hint", evalCase.manualHint());
            }
            cand.put("baseline", Map.of(
                    "answer", b.get("answer").asText(),
                    "hits", topN(b.get("hits"), 3)));
            cand.put("latest", Map.of(
                    "answer", l.get("answer").asText(),
                    "hits", topN(l.get("hits"), 3)));
            candidates.add(cand);
        }

        md.insert(md.indexOf("## Signals"),
                "## Summary\n- candidates: " + candidates.size()
                        + "\n- max severity: " + maxSeverity + "\n\n");

        ReportWriter.writeText(md.toString(), ReportWriter.reportsDir().resolve("diff-report.md"));
        ReportWriter.writeJson(candidates, ReportWriter.reportsDir().resolve("candidates.json"));

        System.out.printf("DIFF DONE — candidates=%d maxSeverity=%d%n", candidates.size(), maxSeverity);

        if (maxSeverity >= 4) {
            fail("Regression detected (severity " + maxSeverity + "). See diff-report.md.");
        }
    }

    private static Map<String, JsonNode> indexById(JsonNode arr) {
        Map<String, JsonNode> m = new LinkedHashMap<>();
        arr.forEach(n -> m.put(n.get("id").asText(), n));
        return m;
    }

    private static DiffEngine.CaseSnapshot toSnapshot(JsonNode n) throws Exception {
        JsonNode checks = n.get("checks");
        List<String> mcMiss = M.convertValue(checks.get("must_contain_missing"),
                new TypeReference<List<String>>() {});
        List<String> mncHit = M.convertValue(checks.get("must_not_contain_hit"),
                new TypeReference<List<String>>() {});
        String hsHit = checks.get("hard_stop_hit").isNull() ? null : checks.get("hard_stop_hit").asText();
        int wc = checks.get("word_count").asInt();
        boolean mwv = checks.get("max_words_violated").asBoolean();

        String topChunk = null;
        JsonNode hits = n.get("hits");
        if (hits != null && hits.isArray() && hits.size() > 0) {
            topChunk = hits.get(0).get("chunkId").asText(null);
        }

        return new DiffEngine.CaseSnapshot(
                n.get("verdict").asText(),
                mcMiss, mncHit, hsHit, wc, mwv,
                n.get("answer").asText(""), topChunk);
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s.replace('\n', ' ') : s.substring(0, n).replace('\n', ' ') + "...";
    }

    private static List<JsonNode> topN(JsonNode hits, int n) {
        List<JsonNode> result = new ArrayList<>();
        if (hits != null && hits.isArray()) {
            for (int i = 0; i < Math.min(hits.size(), n); i++) {
                result.add(hits.get(i));
            }
        }
        return result;
    }
}
