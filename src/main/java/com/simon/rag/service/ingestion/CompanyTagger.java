package com.simon.rag.service.ingestion;

import com.simon.rag.config.RagProperties;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Tags a chunk with the company names it belongs to, merging signals from the
 * markdown heading, the filename, and the chunk text, then reverse-deriving
 * parent companies from the configured company-subgroups.
 */
@Component
@RequiredArgsConstructor
public class CompanyTagger {

    private final RagProperties ragProperties;

    /**
     * Merges companies from heading metadata + chunk text + filename.
     * If the markdown heading contains a company name, it takes priority as the
     * most reliable signal for this specific chunk.
     */
    public List<String> tag(String fileName, TextSegment segment) {
        Set<String> companies = new LinkedHashSet<>();

        String heading = segment.metadata().getString("heading");
        if (heading != null && !heading.isBlank()) {
            companies.addAll(extractCompaniesFromText(heading));
        }

        if (companies.isEmpty()) {
            companies.addAll(extractCompaniesFromText(fileName));
        }

        companies.addAll(extractCompaniesFromText(segment.text()));

        List<String> result = new ArrayList<>(companies);
        addParentCompanies(result);
        return result;
    }

    /** Returns all configured company names found in the given text (case-insensitive). */
    private List<String> extractCompaniesFromText(String text) {
        String lower = text.toLowerCase();
        return ragProperties.getCompanies().stream()
                .filter(c -> lower.contains(c.toLowerCase()))
                .toList();
    }

    /**
     * Reverse-derives parent companies from company-subgroups config.
     * If a chunk is tagged [OCBC], it also gets [Deloitte] so parent-company
     * searches can find sub-project chunks without sub-project query expansion.
     */
    private void addParentCompanies(List<String> companies) {
        ragProperties.getCompanySubgroups().forEach((parent, subs) -> {
            for (String sub : subs) {
                if (companies.contains(sub) && !companies.contains(parent)) {
                    companies.add(parent);
                }
            }
        });
    }
}
