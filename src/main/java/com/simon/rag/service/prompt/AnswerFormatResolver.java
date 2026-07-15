package com.simon.rag.service.prompt;

import com.simon.rag.comm.enums.PromptMode;
import com.simon.rag.comm.enums.QuestionType;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AnswerFormatResolver {

    // if question is BEHAVIORAL and contains words, means question is MULTI_COMPANY_BEHAVIORAL
    public static final List<String> BEHAVIORAL_THEMES = List.of(
            "leadership", "led", "conflict", "ownership", "mentoring", "mentored",
            "initiative", "proactive", "ambiguity", "disagreement",
            "demonstrated", "showed", "handled", "managed", "delivery"
    );

    /**
     * Resolves the output format mode from question type + retrieval strategy.
     * No new classifier needed — scope is already encoded in strategyName.
     */
    public PromptMode resolve(QuestionType type, String strategyName, String question) {
        if ("MULTI_COMPANY".equals(strategyName)) {
            return resolveMultiCompany(type, question);
        }
        return resolveSingleScope(type);
    }

    private PromptMode resolveMultiCompany(QuestionType type, String question) {
        String lower = question.toLowerCase();
        boolean hasBehavioralTheme = type == QuestionType.BEHAVIORAL
                || BEHAVIORAL_THEMES.stream().anyMatch(lower::contains);
        return hasBehavioralTheme
                ? PromptMode.MULTI_COMPANY_BEHAVIORAL
                : PromptMode.MULTI_COMPANY_SUMMARY;
    }

    private PromptMode resolveSingleScope(QuestionType type) {
        return switch (type) {
            case BEHAVIORAL            -> PromptMode.SINGLE_STAR;
            case FACTUAL               -> PromptMode.FACTUAL_SHORT;
            case TECHNICAL, STRATEGIC  -> PromptMode.DEFAULT;
            default                    -> PromptMode.DEFAULT;
        };
    }
}