package com.simon.rag.service.impl;

import org.springframework.stereotype.Component;

@Component
public class PromptBuilder {

    public String build(String question, String context) {
        return "You are Simon's professional AI assistant. " +
                "Answer questions about Simon's background, skills, and experience " +
                "based solely on the provided context. " +
                "Rules: " +
                "- Max 2-4 sentences max " +
                "- Be concise and factual " +
                "- No explanations, no filler words " +
                "- No bullet points or markdown .\n\n" +
                "Context:\n" + context + "\n\n" +
                "Question: " + question + "\n\n" +
                "Answer:";
    }
}
