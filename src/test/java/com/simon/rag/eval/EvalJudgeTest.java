package com.simon.rag.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simon.rag.model.eval.EvalCase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Tag("eval-judge")
class EvalJudgeTest {

    private static final ObjectMapper M = new ObjectMapper();

    @Test
    void judge() throws Exception {
        Path candPath = ReportWriter.reportsDir().resolve("candidates.json");
        if (!Files.exists(candPath)) {
            System.out.println("No candidates.json — run -Dgroups=eval-diff first.");
            return;
        }

        Path latestPath = ReportWriter.reportsDir().resolve("latest.json");
        String runId = Files.exists(latestPath)
                ? M.readTree(latestPath.toFile()).path("runId").asText("unknown")
                : "unknown";

        String apiKey = env("DEEPSEEK_API_KEY", "");
        int maxN = Integer.parseInt(env("EVAL_JUDGE_MAX", System.getProperty("eval.judge.max", "10")));
        String model = env("EVAL_JUDGE_MODEL", "deepseek-chat");
        String baseUrl = env("EVAL_JUDGE_URL", "https://api.deepseek.com");
        int timeout = Integer.parseInt(env("EVAL_JUDGE_TIMEOUT", "30"));

        List<JsonNode> all = M.readValue(candPath.toFile(),
                new TypeReference<List<JsonNode>>() {});
        if (all.isEmpty()) {
            ReportWriter.writeText("# Judge Report\n\nNo diff candidates — nothing to judge.\n",
                    ReportWriter.reportsDir().resolve("judge-report.md"));
            return;
        }

        all.sort(Comparator.comparingInt((JsonNode n) -> n.path("severity").asInt()).reversed());
        boolean truncated = all.size() > maxN;
        List<JsonNode> picks = truncated ? all.subList(0, maxN) : all;

        StringBuilder md = new StringBuilder();
        md.append("## Judge Report  run=").append(runId)
          .append("  candidates=").append(picks.size()).append('/').append(all.size()).append("\n\n");
        if (truncated) md.append("> WARN: only top ").append(maxN).append(" by severity were judged.\n\n");
        if (apiKey.isBlank()) {
            md.append("> WARN: DEEPSEEK_API_KEY not set — judge skipped.\n");
            ReportWriter.writeText(md.toString(), ReportWriter.reportsDir().resolve("judge-report.md"));
            return;
        }

        JudgeClient client = new JudgeClient(apiKey, baseUrl, model, 0.0, timeout);

        List<EvalCase> cases = CaseLoader.load();
        Map<String, EvalCase> byId = new HashMap<>();
        cases.forEach(c -> byId.put(c.id(), c));

        var sections = new LinkedHashMap<String, List<String>>();
        sections.put("REGRESS", new ArrayList<>());
        sections.put("PASS", new ArrayList<>());
        sections.put("IMPROVE", new ArrayList<>());

        for (JsonNode cand : picks) {
            String id = cand.get("caseId").asText();
            int sev = cand.get("severity").asInt();
            String label = cand.get("signal").asText();
            var aCase = byId.get(id);

            String answerA = cand.path("baseline").path("answer").asText("");
            String answerB = cand.path("latest").path("answer").asText("");

            String entry;
            try {
                var v = client.judge(
                        aCase.question(), aCase.manualHint(),
                        aCase.mustContain(), aCase.mustNotContain(),
                        answerA, answerB);

                entry = String.format(
                        "#### %s  severity=%d  type=%s%n" +
                        "- Diff signal:  %s%n" +
                        "- Judge:        faithfulness %d→%d, completeness %d→%d, tone %d→%d%n" +
                        "- Reason:       %s%n%n",
                        id, sev, aCase.type(),
                        label,
                        v.faithA(), v.faithB(),
                        v.complA(), v.complB(),
                        v.toneA(),  v.toneB(),
                        v.reason());

                sections.get(v.verdict()).add(entry);

            } catch (Exception e) {
                String err = String.format("#### %s  severity=%d  JUDGE_ERROR%n- %s: %s%n%n",
                        id, sev, e.getClass().getSimpleName(), e.getMessage());
                sections.get("PASS").add(err);
            }
        }

        sections.forEach((verdict, entries) -> {
            md.append("### ").append(verdict).append(" (").append(entries.size()).append(")\n\n");
            entries.forEach(md::append);
        });

        ReportWriter.writeText(md.toString(), ReportWriter.reportsDir().resolve("judge-report.md"));
        System.out.println("JUDGE DONE → judge-report.md");
    }

    private static String env(String k, String dflt) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? dflt : v;
    }
}
