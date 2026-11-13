package com.simon.rag.service.retrieval;

import com.simon.rag.config.RagProperties;
import com.simon.rag.service.cache.ConversationHistory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Resolves company scope for retrieval: focus-company detection, sub-project query
 * expansion, sibling companies, and the company post-filter over reranked hits.
 */
@Component
@RequiredArgsConstructor
public class CompanyScopeResolver {

    private final RagProperties ragProperties;

    /**
     * Detects the company a question is about. Priority: subsidiary named in the
     * question → any company named in the question → conversation history fallback
     * (interview sessions typically stay on one topic, so the last explicitly named
     * company is almost always the correct context — questions are authoritative,
     * answers are a secondary fallback).
     */
    public String extractFocusCompany(String question, ConversationHistory history) {
        String lowerQuestion = question.toLowerCase();
        String subsidiary = findSubsidiaryCompany(lowerQuestion);
        if (subsidiary != null) return subsidiary;

        String fromQuestion = findCompany(lowerQuestion);
        if (fromQuestion != null) return fromQuestion;

        List<ConversationHistory.Turn> turns = history.turns();
        for (int i = turns.size() - 1; i >= 0; i--) {
            String found = findCompany(turns.get(i).question().toLowerCase());
            if (found != null) return found;
        }
        for (int i = turns.size() - 1; i >= 0; i--) {
            String found = findCompany(turns.get(i).answer().toLowerCase());
            if (found != null) return found;
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

    /**
     * Deloitte delivered two client projects (OCBC and Sanofi). When a question is about
     * Deloitte, the original query alone scores OCBC chunks much higher than Sanofi chunks
     * (different vocabulary). Adding explicit sub-project variants ensures both projects'
     * chunks are retrievable via multi-search.
     */
    public List<String> withSubProjectQueries(List<String> queries, String focusCompany) {
        if (focusCompany == null) return queries;
        List<String> subgroups = ragProperties.getCompanySubgroups()
                .getOrDefault(focusCompany, List.of());
        if (subgroups.isEmpty()) return queries;
        String original = queries.get(0);
        String pattern = "(?i)\\b" + Pattern.quote(focusCompany) + "\\b";
        List<String> expanded = new ArrayList<>(queries);
        for (String sub : subgroups) {
            String replaced = original.replaceAll(pattern, sub);
            if (!replaced.equals(original)) expanded.add(replaced);
        }
        return expanded;
    }

    /**
     * Returns sibling sub-companies for a sub-project focusCompany.
     * Siblings share the same parent group and are ALLOWED in the post-filter.
     * e.g. focusCompany=OCBC → siblings=[Sanofi] (both are Deloitte sub-projects).
     */
    public List<String> getSiblingCompanies(String focusCompany) {
        if (focusCompany == null) return List.of();
        return ragProperties.getCompanySubgroups().values().stream()
                .filter(subs -> subs.contains(focusCompany))
                .flatMap(Collection::stream)
                .filter(c -> !c.equals(focusCompany))
                .toList();
    }

    /** Company-scoped post-filter on reranked hits. */
    public List<RerankedHit> applyCompanyFilter(List<RerankedHit> ranked,
                                                String focusCompany, List<String> siblings) {
        if (focusCompany == null) return ranked;
        Set<String> allowed = buildAllowedCompanies(focusCompany, siblings);
        return ranked.stream()
                .filter(rh -> {
                    List<String> cos = rh.hit().companies();
                    if (cos.isEmpty()) return true;
                    if (cos.stream().noneMatch(c -> c.equalsIgnoreCase(focusCompany))) return false;
                    return cos.stream().allMatch(
                            c -> allowed.stream().anyMatch(a -> a.equalsIgnoreCase(c)));
                })
                .collect(Collectors.toList());
    }

    /**
     * The set of company labels "legitimate" for a given focus company:
     * the focus company itself, its siblings, and any parent firm (e.g. Deloitte for OCBC/Sanofi).
     */
    private Set<String> buildAllowedCompanies(String focusCompany, List<String> siblings) {
        Set<String> allowed = new HashSet<>();
        allowed.add(focusCompany);
        allowed.addAll(siblings);
        ragProperties.getCompanySubgroups().forEach((parent, subs) -> {
            if (subs.contains(focusCompany)) allowed.add(parent);
            if (parent.equalsIgnoreCase(focusCompany)) allowed.addAll(subs);
        });
        return allowed;
    }
}
