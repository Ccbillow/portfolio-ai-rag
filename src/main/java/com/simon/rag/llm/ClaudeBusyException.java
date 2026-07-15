package com.simon.rag.llm;

/**
 * Thrown by {@link LlmThrottle} when the required wait to acquire a rate-limit
 * slot would exceed the configured bound — signals the caller to fast-fail with a
 * "please try again" response instead of blocking a request thread indefinitely.
 */
public class ClaudeBusyException extends RuntimeException {
    public ClaudeBusyException(long waitMs, long maxWaitMs) {
        super("Claude rate-limit wait " + waitMs + "ms exceeds bound " + maxWaitMs + "ms");
    }
}
