package com.simon.rag.service.cache;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured view of a session's conversation history.
 *
 * <p>Owns the parsing of the {@code "Q: ...\nA: ..."} storage format written by
 * {@link RedisCacheService#appendConversationHistory} — callers (retrieval, prompt)
 * never touch the raw format, so storage changes can't silently break them.
 */
public record ConversationHistory(List<Turn> turns, String promptText) {

    public record Turn(String question, String answer) {}

    public static final ConversationHistory EMPTY = new ConversationHistory(List.of(), "");

    /** Parses raw Redis list entries (one {@code "Q: q\nA: a"} block per turn). */
    static ConversationHistory fromEntries(List<String> entries) {
        if (entries == null || entries.isEmpty()) return EMPTY;
        List<Turn> turns = new ArrayList<>(entries.size());
        for (String entry : entries) {
            int aIdx = entry.indexOf("\nA: ");
            String question = entry.startsWith("Q: ")
                    ? entry.substring(3, aIdx < 0 ? entry.length() : aIdx).strip() : "";
            String answer = aIdx >= 0 ? entry.substring(aIdx + 4).strip() : "";
            turns.add(new Turn(question, answer));
        }
        return new ConversationHistory(List.copyOf(turns), String.join("\n\n", entries));
    }

    public boolean isEmpty() {
        return turns.isEmpty();
    }

    /** Question of the most recent turn, or "" when empty. */
    public String lastQuestion() {
        return turns.isEmpty() ? "" : turns.get(turns.size() - 1).question();
    }

    /** Most recent non-blank answer across turns, or "" when none. */
    public String lastAnswer() {
        for (int i = turns.size() - 1; i >= 0; i--) {
            if (!turns.get(i).answer().isBlank()) return turns.get(i).answer();
        }
        return "";
    }
}
