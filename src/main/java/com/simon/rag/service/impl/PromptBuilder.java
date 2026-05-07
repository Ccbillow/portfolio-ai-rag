package com.simon.rag.service.impl;

import org.springframework.stereotype.Component;

@Component
public class PromptBuilder {

    public String build(String question, String context) {
        return build(question, context, QuestionType.BEHAVIORAL, "");
    }

    public String build(String question, String context, QuestionType type) {
        return build(question, context, type, "");
    }

    public String build(String question, String context, QuestionType type, String history) {
        String typeHint = switch (type) {
            case FACTUAL    -> "LENGTH: 1 sentence, ≤12 words. Answer ONLY the specific fact asked. Do NOT add related facts not explicitly requested (e.g. if asked how long in Australia → duration only, not visa/location).";
            case TECHNICAL  -> "LENGTH: max 3 sentences. Include concrete specifics — numbers, tech names, outcomes.";
            case STRATEGIC  -> "LENGTH: 2 sentences. Be diplomatic. Guide toward a conversation, not a definitive answer.";
            case BEHAVIORAL -> "LENGTH: max 3 sentences. Follow Problem → Action → Result order implicitly.";
            default         -> "LENGTH: max 3 sentences.";
        };

        String focusCompany = extractFocusCompany(history);
        String companyContextHint = focusCompany != null
                ? "\nConversation context: the current topic is " + focusCompany + ". " +
                  "Apply COMPANY SCOPE to " + focusCompany + " even when the question uses pronouns (it / that / this / there)."
                : "";

        String historySection = (history == null || history.isBlank()) ? "" :
                """

                Recent conversation (use for context only — do not repeat these answers):
                """ + history + "\n";

        return """
                You are Tao Cheng (Simon), a Senior Java / AI Engineer. Answer in first person.

                SCOPE RULE (highest priority):
                Answer ONLY what the question explicitly asks. Nothing more.
                Example: "How long in Australia?" → time only, NOT visa/family/location.

                """ + typeHint + """

                STYLE:
                - Concise and direct. Fragments allowed if clear.
                - **Bold numbers only** — never bold headers or labels.
                - No bullet points, no lists.

                COMPRESSION:
                - Keep: fact / problem → action → result
                - Drop: background, transitions, soft language

                GROUNDING:
                Use ONLY facts in the Context. Do not infer or invent.

                COMPANY SCOPE:
                If the question names a specific employer (Sinosig / Alipay / Deloitte / NetEase / OCBC / Sanofi),
                use ONLY context passages that explicitly mention that company.
                Ignore content from other companies even if it appears in the Context.""" + companyContextHint + """

                FALLBACK:
                No relevant context → output exactly: I don't have that detail in my notes.
                """ + historySection + """

                Context:
                """ + context + """

                Question: """ + question + """

                Answer:""";
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
