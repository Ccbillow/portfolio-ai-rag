package com.simon.rag.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PromptBuilder {

    private final PromptTemplateService promptTemplateService;

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
                ? "\nConversation context: the current topic is " + focusCompany + ". " +
                  "Apply COMPANY SCOPE to " + focusCompany + " even when the question uses pronouns (it / that / this / there)."
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

    String extractFocusCompany(String question, String history) {
        // Current question always takes priority
        String fromQuestion = findCompany(question.toLowerCase());
        if (fromQuestion != null) return fromQuestion;

        // Only use history when question is ambiguous (pronoun reference)
        if (history == null || history.isBlank()) return null;
        boolean hasAmbiguity = question.toLowerCase()
                .matches(".*(\\bit\\b|\\bthat\\b|\\bthere\\b|\\bthis\\b|\\bthey\\b|\\bthose\\b).*");
        if (!hasAmbiguity) return null;

        // Extract company from the last answer only — not the whole history
        return findCompany(extractLastAnswer(history).toLowerCase());
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
}
