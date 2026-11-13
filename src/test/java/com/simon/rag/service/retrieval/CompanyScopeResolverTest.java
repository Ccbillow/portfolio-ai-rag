package com.simon.rag.service.retrieval;

import com.simon.rag.config.RagProperties;
import com.simon.rag.service.cache.ConversationHistory;
import com.simon.rag.service.vectorstore.QdrantSearchService.SearchHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class CompanyScopeResolverTest {

    private CompanyScopeResolver resolver;

    @BeforeEach
    void setUp() {
        RagProperties props = new RagProperties();
        props.setCompanies(List.of("Alipay", "Sinosig", "NetEase", "Deloitte", "OCBC", "Sanofi"));
        props.setCompanySubgroups(Map.of("Deloitte", List.of("OCBC", "Sanofi")));
        resolver = new CompanyScopeResolver(props);
    }

    private static RerankedHit hit(int chunkIndex, String... companies) {
        return new RerankedHit(
                new SearchHit(0.5, "t", "f.md", "cat", "doc1", chunkIndex, List.of(companies)), 0.5);
    }

    private static ConversationHistory history(String question, String answer) {
        return new ConversationHistory(List.of(new ConversationHistory.Turn(question, answer)), "");
    }

    // ── extractFocusCompany ──────────────────────────────────────────

    @Test
    void focus_subsidiaryInQuestion_winsOverParent() {
        assertEquals("OCBC", resolver.extractFocusCompany(
                "What did you build for OCBC at Deloitte?", ConversationHistory.EMPTY));
    }

    @Test
    void focus_companyInQuestion() {
        assertEquals("Alipay", resolver.extractFocusCompany(
                "What was your role at alipay?", ConversationHistory.EMPTY));
    }

    @Test
    void focus_fallsBackToHistoryQuestion_thenHistoryAnswer() {
        assertEquals("Sinosig", resolver.extractFocusCompany(
                "What was the hardest part?", history("Tell me about Sinosig", "I built ...")));
        assertEquals("NetEase", resolver.extractFocusCompany(
                "What was the hardest part?", history("Tell me more", "At NetEase I built ...")));
    }

    @Test
    void focus_noCompanyAnywhere_isNull() {
        assertNull(resolver.extractFocusCompany("What are your strengths?", ConversationHistory.EMPTY));
    }

    // ── siblings / sub-project queries ───────────────────────────────

    @Test
    void siblings_ofSubProject_areTheOtherSubProjects() {
        assertEquals(List.of("Sanofi"), resolver.getSiblingCompanies("OCBC"));
        assertEquals(List.of(), resolver.getSiblingCompanies("Alipay"));
        assertEquals(List.of(), resolver.getSiblingCompanies(null));
    }

    @Test
    void subProjectQueries_parentCompany_addsOneVariantPerSubProject() {
        assertEquals(
                List.of("What did you do at Deloitte?", "What did you do at OCBC?", "What did you do at Sanofi?"),
                resolver.withSubProjectQueries(List.of("What did you do at Deloitte?"), "Deloitte"));
    }

    @Test
    void subProjectQueries_companyWithoutSubgroups_unchanged() {
        List<String> queries = List.of("What did you do at Alipay?");
        assertSame(queries, resolver.withSubProjectQueries(queries, "Alipay"));
        assertSame(queries, resolver.withSubProjectQueries(queries, null));
    }

    // ── applyCompanyFilter ───────────────────────────────────────────

    @Test
    void filter_noFocusCompany_returnsInputUntouched() {
        List<RerankedHit> ranked = List.of(hit(0, "Alipay"), hit(1, "OCBC"));
        assertSame(ranked, resolver.applyCompanyFilter(ranked, null, List.of()));
    }

    @Test
    void filter_subProjectFocus_keepsFocusSiblingsParent_dropsUnrelated() {
        RerankedHit focusOnly      = hit(0, "OCBC");
        RerankedHit engagementMix  = hit(1, "OCBC", "Sanofi", "Deloitte");
        RerankedHit unrelatedMix   = hit(2, "OCBC", "Alipay");
        RerankedHit siblingOnly    = hit(3, "Sanofi");
        RerankedHit untagged       = hit(4);

        List<RerankedHit> out = resolver.applyCompanyFilter(
                List.of(focusOnly, engagementMix, unrelatedMix, siblingOnly, untagged),
                "OCBC", List.of("Sanofi"));

        assertEquals(List.of(focusOnly, engagementMix, untagged), out);
    }

    @Test
    void filter_parentFocus_allowsItsSubProjects() {
        RerankedHit parentWithChild = hit(0, "Deloitte", "OCBC");
        RerankedHit parentWithOther = hit(1, "Deloitte", "Alipay");

        List<RerankedHit> out = resolver.applyCompanyFilter(
                List.of(parentWithChild, parentWithOther), "Deloitte", List.of());

        assertEquals(List.of(parentWithChild), out);
    }
}
