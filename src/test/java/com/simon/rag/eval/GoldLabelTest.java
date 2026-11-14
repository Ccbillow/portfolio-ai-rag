package com.simon.rag.eval;

import com.simon.rag.config.RagProperties;
import com.simon.rag.model.eval.EvalCase;
import com.simon.rag.service.cache.RedisCacheService;
import com.simon.rag.service.retrieval.RetrievalResult;
import com.simon.rag.service.retrieval.RetrievalService;
import com.simon.rag.service.vectorstore.QdrantSearchService.SearchHit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Gold-label bootstrap: pools candidate chunks across every ablation config (so the
 * labels are not biased toward what the production config already finds), lets the
 * judge model pre-select gold chunks, and writes
 * <ul>
 *   <li>{@code gold-labels.json} — caseId → [fileName#chunkIndex], the file to edit</li>
 *   <li>{@code eval-reports/gold-review-report.md} — every candidate with its text, pre-label ticked</li>
 * </ul>
 * Run: {@code make eval-label}. Refuses to overwrite reviewed labels unless
 * {@code -Deval.label.overwrite=true}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("eval-label")
class GoldLabelTest {

    @Autowired private RetrievalService retrievalService;
    @Autowired private RedisCacheService redisCacheService;
    @Autowired private RagProperties ragProperties;

    @Test
    void preLabel() throws Exception {
        if (Files.exists(RetrievalEvalSupport.GOLD_LABELS) && !Boolean.getBoolean("eval.label.overwrite")) {
            fail(RetrievalEvalSupport.GOLD_LABELS + " exists — pass -Deval.label.overwrite=true to regenerate");
        }
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) fail("DEEPSEEK_API_KEY not set");
        JudgeClient judge = new JudgeClient(apiKey, "https://api.deepseek.com", "deepseek-chat", 0.0, 60);

        Map<String, List<String>> gold = new LinkedHashMap<>();
        StringBuilder md = new StringBuilder("# Gold label review\n\n")
                .append("`[x]` = pre-labelled gold. To correct a case, edit its key list in `")
                .append(RetrievalEvalSupport.GOLD_LABELS).append("`.\n\n");

        List<EvalCase> cases = CaseLoader.load();
        int n = 0;
        for (EvalCase c : cases) {
            n++;
            Map<String, String> pool = new LinkedHashMap<>();
            boolean earlyReturn = false;
            for (RetrievalEvalSupport.Config cfg : RetrievalEvalSupport.ABLATION) {
                RetrievalResult r = RetrievalEvalSupport.with(ragProperties, cfg,
                        () -> RetrievalEvalSupport.retrieve(c, retrievalService, redisCacheService));
                if (r.hasEarlyReturn()) { earlyReturn = true; break; }
                for (SearchHit h : r.stages().candidates()) pool.putIfAbsent(RetrievalEvalSupport.key(h), h.text());
                r.hits().forEach(rh -> pool.putIfAbsent(RetrievalEvalSupport.key(rh.hit()), rh.hit().text()));
            }
            if (earlyReturn) {
                System.out.printf("[%2d/%2d] %s early return — skipped%n", n, cases.size(), c.id());
                continue;
            }

            List<String> picked = judge.selectGold(c.question(), c.manualHint(), c.mustContain(), pool);
            gold.put(c.id(), picked);
            System.out.printf("[%2d/%2d] %s pool=%d gold=%s%n", n, cases.size(), c.id(), pool.size(), picked);

            md.append("## ").append(c.id()).append(" — ").append(c.question()).append("\n\n");
            if (c.setupTurns() != null && !c.setupTurns().isEmpty())
                md.append("- setup turns: ").append(c.setupTurns()).append('\n');
            md.append("- hint: ").append(c.manualHint()).append('\n');
            md.append("- must_contain: ").append(c.mustContain()).append("\n\n");
            pool.forEach((k, text) -> md.append(picked.contains(k) ? "- [x] " : "- [ ] ")
                    .append('`').append(k).append("` ")
                    .append(text.replaceAll("\\s+", " ").strip(), 0,
                            Math.min(300, text.replaceAll("\\s+", " ").strip().length()))
                    .append('\n'));
            md.append('\n');

            // incremental write so a mid-run failure keeps what was labelled
            ReportWriter.writeJson(gold, RetrievalEvalSupport.GOLD_LABELS);
            ReportWriter.writeText(md.toString(), ReportWriter.reportsDir().resolve("gold-review-report.md"));
        }
        System.out.println("PRE-LABEL DONE → " + RetrievalEvalSupport.GOLD_LABELS + " + gold-review-report.md");
    }
}
