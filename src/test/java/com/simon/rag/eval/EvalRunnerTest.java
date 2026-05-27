package com.simon.rag.eval;

import com.simon.rag.model.eval.EvalCase;
import com.simon.rag.model.eval.EvalResult;
import com.simon.rag.service.EvalChatService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@SpringBootTest
@ActiveProfiles("test")
@Tag("eval-run")
class EvalRunnerTest {

    @Autowired
    private EvalChatService evalChatService;

    @Value("${langchain4j.anthropic.chat-model-name:claude-haiku-4-5-20251001}")
    private String modelName;

    @Test
    void runAllCases() {
        List<EvalCase> cases = CaseLoader.load();
        String runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        int pass = 0, fail = 0;
        int i = 0;

        for (EvalCase aCase : cases) {
            i++;
            EvalResult result = evalChatService.evaluate(aCase);
            ChecksRunner.ChecksResult checks = ChecksRunner.run(
                    aCase, result.answer() == null ? "" : result.answer());

            if ("PASS".equals(checks.verdict())) pass++; else fail++;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", aCase.id());
            row.put("type", aCase.type());
            row.put("question", aCase.question());
            row.put("answer", result.answer());
            Map<String, Object> checksMap = new LinkedHashMap<>();
            checksMap.put("must_contain_missing", checks.mustContainMissing());
            checksMap.put("must_not_contain_hit", checks.mustNotContainHit());
            checksMap.put("hard_stop_hit", checks.hardStopHit());
            checksMap.put("word_count", checks.wordCount());
            checksMap.put("max_words_violated", checks.maxWordsViolated());
            row.put("checks", checksMap);
            row.put("verdict", checks.verdict());
            row.put("hits", result.hits());
            row.put("tokens", result.tokens());
            row.put("latency", result.latency());
            if (result.error() != null) row.put("error", result.error());
            rows.add(row);

            // print per-case result; detail failures immediately
            String label = String.format("[%s] %2d/%2d %s %-12s %s",
                    checks.verdict(), i, cases.size(), aCase.id(), aCase.type(),
                    aCase.question().substring(0, Math.min(50, aCase.question().length())));
            System.out.println(label);
            if ("FAIL".equals(checks.verdict())) {
                if (!checks.mustContainMissing().isEmpty())
                    System.out.println("  missing: " + checks.mustContainMissing());
                if (!checks.mustNotContainHit().isEmpty())
                    System.out.println("  forbidden: " + checks.mustNotContainHit());
                if (checks.hardStopHit() != null)
                    System.out.println("  hard_stop: " + checks.hardStopHit());
                if (checks.maxWordsViolated())
                    System.out.println("  words: " + checks.wordCount() + " > max");
                if (result.error() != null)
                    System.out.println("  error: " + result.error());
            }

            // incremental write so partial results survive early termination
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("runId", runId);
            envelope.put("gitSha", gitSha());
            envelope.put("model", modelName);
            envelope.put("cases", rows);
            envelope.put("summary", Map.of("total", cases.size(),
                    "done", i, "pass", pass, "fail", fail));
            ReportWriter.writeJson(envelope, ReportWriter.reportsDir().resolve("latest.json"));

            // Pace eval cases to avoid overwhelming Cohere / Claude APIs
            try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }

        System.out.printf("EVAL DONE — pass=%d fail=%d → latest.json%n", pass, fail);
    }

    private static String gitSha() {
        try {
            Process p = new ProcessBuilder("git","rev-parse","--short","HEAD").start();
            return new String(p.getInputStream().readAllBytes()).trim();
        } catch (Exception e) { return "unknown"; }
    }
}
