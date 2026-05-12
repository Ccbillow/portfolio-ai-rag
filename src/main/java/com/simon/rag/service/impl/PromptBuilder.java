package com.simon.rag.service.impl;

import com.simon.rag.config.RagProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class PromptBuilder {

    private final PromptTemplateService promptTemplateService;
    private final RagProperties ragProperties;

    public String build(String question, String context) {
        return build(question, context, QuestionType.BEHAVIORAL, "");
    }

    public String build(String question, String context, QuestionType type) {
        return build(question, context, type, "");
    }

    public String build(String question, String context, QuestionType type, String history) {
        String typeHintKey = switch (type) {
            case FACTUAL    -> "type_hint_factual";
            case TECHNICAL  -> "type_hint_technical";
            case STRATEGIC  -> "type_hint_strategic";
            case BEHAVIORAL -> "type_hint_behavioral";
            default         -> "type_hint_default";
        };
        String typeHint = promptTemplateService.get(typeHintKey);

        String focusCompany = extractFocusCompany(question, history);
        String companyContextHint = focusCompany != null
                ? buildCompanyContextHint(focusCompany)
                : "";

        String historySection = (history == null || history.isBlank()) ? "" :
                "\nRecent conversation (use for context only — do not repeat these answers):\n" + history + "\n";

        return promptTemplateService.get("system_prompt")
                .replace("{{typeHint}}", typeHint)
                .replace("{{companyContextHint}}", companyContextHint)
                .replace("{{historySection}}", historySection)
                .replace("{{context}}", context)
                .replace("{{question}}", question);
    }

    private String buildCompanyContextHint(String focusCompany) {
        // List every other known company so the LLM ignores their content when it leaks into context
        // via mixed-label chunks (e.g. an overview chunk tagged ["OCBC","Alipay","Sanofi",...]).
        List<String> others = ragProperties.getCompanies().stream()
                .filter(c -> !c.equalsIgnoreCase(focusCompany))
                .collect(Collectors.toList());
        String exclusion = others.isEmpty() ? "" :
                " Use ONLY " + focusCompany + "-related facts from the Context." +
                " If the Context contains information about " + String.join(", ", others) +
                ", ignore it completely — do not use it in your answer.";
        return "\nConversation context: the current topic is " + focusCompany + ". " +
               "Apply COMPANY SCOPE to " + focusCompany +
               " even when the question uses pronouns (it / that / this / there)." +
               exclusion;
    }

    String extractFocusCompany(String question, String history) {
        // Current question always takes priority
        String fromQuestion = findCompany(question.toLowerCase());
        if (fromQuestion != null) return fromQuestion;

        // Fallback: use conversation history — interview sessions typically stay on one topic,
        // so the last explicitly named company is almost always the correct context.
        // Scan Q: lines first (user questions are authoritative);
        // A: lines are a secondary fallback only.
        if (history == null || history.isBlank()) return null;
        String[] lines = history.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith("Q: ")) {
                String found = findCompany(line.substring(3).strip().toLowerCase());
                if (found != null) return found;
            }
        }
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith("A: ")) {
                String found = findCompany(line.substring(3).strip().toLowerCase());
                if (found != null) return found;
            }
        }
        return null;
    }

    private String findCompany(String text) {
        if (text.contains("alipay"))   return "Alipay";
        if (text.contains("sinosig"))  return "Sinosig";
        if (text.contains("sanofi"))   return "Sanofi";
        if (text.contains("ocbc"))     return "OCBC";
        if (text.contains("deloitte")) return "Deloitte";
        if (text.contains("netease"))  return "NetEase";
        return null;
    }

    private String extractLastAnswer(String history) {
        String last = "";
        for (String line : history.split("\n")) {
            if (line.trim().startsWith("A: ")) last = line.trim().substring(3).strip();
        }
        return last;
    }

    private String extractLastQuestion(String history) {
        String last = "";
        for (String line : history.split("\n")) {
            if (line.trim().startsWith("Q: ")) last = line.trim().substring(3).strip();
        }
        return last;
    }
}
