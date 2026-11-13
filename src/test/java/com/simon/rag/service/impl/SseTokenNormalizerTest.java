package com.simon.rag.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SseTokenNormalizerTest {

    private final SseTokenNormalizer normalizer = new SseTokenNormalizer();

    private String feed(String... tokens) {
        StringBuilder out = new StringBuilder();
        for (String t : tokens) out.append(normalizer.normalize(t));
        return out.toString();
    }

    @Test
    void plainTokens_passThrough_includingLeadingSpaces() {
        assertEquals("Hello Alipay", feed("Hello", " Alipay"));
    }

    @Test
    void paragraphBreak_insideOneToken_becomesLiteralBackslashN() {
        assertEquals("one\\n\\ntwo", feed("one\n\ntwo"));
    }

    @Test
    void singleNewline_becomesSpace() {
        assertEquals("one two", feed("one\ntwo"));
    }

    @Test
    void paragraphBreak_splitAcrossTwoTokens_isStillAParagraphBreak() {
        assertEquals("one\\n\\ntwo", feed("one\n", "\ntwo"));
    }

    @Test
    void singleNewline_atTokenEnd_becomesSpaceBeforeNextToken() {
        assertEquals("one two", feed("one\n", "two"));
    }

    @Test
    void newlineOnlyToken_emitsNothingUntilResolved() {
        assertEquals("", normalizer.normalize("\n"));
        assertEquals("\\n\\nnext", normalizer.normalize("\nnext"));
    }

    @Test
    void trailingNewline_atEndOfStream_isDropped() {
        assertEquals("done.", feed("done.", "\n"));
    }

    @Test
    void threeNewlines_areParagraphBreakPlusSpace() {
        assertEquals("a\\n\\n b", feed("a\n\n\nb"));
    }
}
