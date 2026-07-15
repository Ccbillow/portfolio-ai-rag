package com.simon.rag.service.retrieval;

import com.simon.rag.service.prompt.AnswerFormatResolver;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Keyword-based query signal detection used for retrieval-strategy selection.
 * Pure functions over the question text — no external dependencies.
 */
@Component
public class QuerySignalClassifier {

    private static final List<String> TEMPORAL_SIGNALS = List.of(
            "how long", "how many years", "how many months", "when did you join",
            "when did you leave", "when did you start", "start date", "end date",
            "tenure", "employment period", "work period", "duration"
    );

    private static final List<String> PROFILE_SIGNALS = List.of(
            "notice period", "visa", "work rights", "nationality", "where are you from",
            "how long in australia", "relocation", "contact preference", "salary",
            "compensation", "package", "current situation",
            "office location", "preferred location", "work location",
            "where (would|do) you (like|want|prefer) to work",
            "working mode", "preferred (working|work) mode",
            "hybrid", "remote", "on.?site", "wfh",
            "where are you based", "based in"
    );

    private static final List<String> MULTI_COMPANY_SIGNALS = List.of(
            "past few jobs", "past jobs", "each company", "all companies",
            "throughout your career", "across your career", "all your experience",
            "different companies", "career highlight", "career journey",
            "walk me through your career", "every company", "all roles"
    );

    public boolean isTemporal(String question) {
        String lower = question.toLowerCase();
        return TEMPORAL_SIGNALS.stream().anyMatch(lower::contains);
    }

    public boolean isProfile(String question) {
        String lower = question.toLowerCase();
        return PROFILE_SIGNALS.stream().anyMatch(lower::contains);
    }

    /**
     * True when the question spans the full career (no specific company in focus).
     * Only fires when focusCompany is null — a detected company means normal scoped
     * retrieval is already correct.
     */
    public boolean isMultiCompany(String question, String focusCompany) {
        if (focusCompany != null) return false;
        String lower = question.toLowerCase();
        return MULTI_COMPANY_SIGNALS.stream().anyMatch(lower::contains);
    }

    public boolean isBehavioralThemed(String question) {
        String lower = question.toLowerCase();
        return AnswerFormatResolver.BEHAVIORAL_THEMES.stream().anyMatch(lower::contains);
    }
}
