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

        String focusCompany = extractFocusCompany(history);
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

    private String extractFocusCompany(String history) {
        if (history == null || history.isBlank()) return null;
        String lower = history.toLowerCase();
        if (lower.contains("alipay"))   return "Alipay";
        if (lower.contains("sinosig"))  return "Sinosig";
        if (lower.contains("sanofi"))   return "Sanofi";
        if (lower.contains("ocbc"))     return "OCBC";
        if (lower.contains("deloitte")) return "Deloitte";
        if (lower.contains("netease"))  return "NetEase";
        return null;
    }
}
