package com.simon.rag.service.impl;

import com.simon.rag.comm.enums.PromptKey;
import com.simon.rag.comm.enums.QuestionType;
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
        return build(question, context, QuestionType.BEHAVIORAL, "", null);
    }

    public String build(String question, String context, QuestionType type) {
        return build(question, context, type, "", null);
    }

    public String build(String question, String context, QuestionType type, String history) {
        return build(question, context, type, history, null);
    }

    public String build(String question, String context, QuestionType type, String history, String focusCompany) {
        PromptKey typeHintKey = switch (type) {
            case FACTUAL    -> PromptKey.TYPE_HINT_FACTUAL;
            case TECHNICAL  -> PromptKey.TYPE_HINT_TECHNICAL;
            case STRATEGIC  -> PromptKey.TYPE_HINT_STRATEGIC;
            case BEHAVIORAL -> PromptKey.TYPE_HINT_BEHAVIORAL;
            default         -> PromptKey.TYPE_HINT_DEFAULT;
        };
        String typeHint = promptTemplateService.get(typeHintKey);

        // Use caller-supplied focusCompany (already computed) to avoid re-scanning history
        if (focusCompany == null) focusCompany = extractFocusCompany(question, history);
        String companyContextHint = focusCompany != null
                ? buildCompanyContextHint(focusCompany)
                : "";

        String historySection = (history == null || history.isBlank()) ? "" :
                "\nRecent conversation (use for context only — do not repeat these answers):\n" + history + "\n";

        return promptTemplateService.get(PromptKey.SYSTEM_PROMPT)
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
        // If the question already names a subsidiary (e.g. "OCBC"), use it directly
        // as focusCompany — no need to expand sub-project queries.
        String lowerQuestion = question.toLowerCase();
        String subsidiary = findSubsidiaryCompany(lowerQuestion);
        if (subsidiary != null) return subsidiary;

        // Current question always takes priority
        String fromQuestion = findCompany(lowerQuestion);
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

    /** Returns a subsidiary company name found in text (any child in company-subgroups). */
    private String findSubsidiaryCompany(String text) {
        return ragProperties.getCompanySubgroups().values().stream()
                .flatMap(List::stream)
                .filter(c -> text.contains(c.toLowerCase()))
                .findFirst()
                .orElse(null);
    }

    private String findCompany(String text) {
        return ragProperties.getCompanies().stream()
                .filter(c -> text.contains(c.toLowerCase()))
                .findFirst()
                .orElse(null);
    }

}
