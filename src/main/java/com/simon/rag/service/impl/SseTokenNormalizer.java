package com.simon.rag.service.impl;

/**
 * Converts LLM tokens to the wire format the chat frontend expects:
 * a paragraph break ({@code \n\n}) becomes the literal four characters {@code \n\n},
 * a single newline becomes a space.
 *
 * <p>Stateful, one instance per stream: a newline at the end of a token is held back
 * until the next token shows whether it was half of a paragraph break. A newline still
 * pending when the stream ends is dropped.
 */
final class SseTokenNormalizer {

    private boolean pendingNewline;

    String normalize(String token) {
        StringBuilder out = new StringBuilder(token.length() + 4);
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '\n') {
                if (pendingNewline) {
                    out.append("\\n\\n");
                    pendingNewline = false;
                } else {
                    pendingNewline = true;
                }
            } else {
                if (pendingNewline) {
                    out.append(' ');
                    pendingNewline = false;
                }
                out.append(c);
            }
        }
        return out.toString();
    }
}
