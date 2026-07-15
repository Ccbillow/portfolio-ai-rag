package com.simon.rag.llm;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Global Claude call timeline shared by ALL call sites (chat, eval, ingestion,
 * classifiers). One Anthropic account = one rate budget: every call advances the
 * same timeline, and each caller declares its own minimum gap since the last call
 * (heavier calls pass a larger gap, hot-path calls pass 0).
 *
 * <p>Replaces the three previously independent throttles (chat, contextual
 * retrieval, metadata enricher) whose combined rate could exceed the account limit.
 */
@Component
public class LlmThrottle {

    private final AtomicLong lastCall = new AtomicLong(0);

    /** Blocks the calling thread until {@code gapMs} has elapsed since the last call. */
    public void acquire(long gapMs) {
        doAcquire(gapMs, Long.MAX_VALUE);
    }

    /**
     * Bounded variant for interactive request threads: if the required wait exceeds
     * {@code maxWaitMs}, fast-fails with {@link ClaudeBusyException} instead of piling
     * request threads up in sleep. As slots fill, each retry computes a larger wait,
     * so both queue depth and latency stay bounded.
     */
    public void acquireBounded(long gapMs, long maxWaitMs) {
        doAcquire(gapMs, maxWaitMs);
    }

    private void doAcquire(long gapMs, long maxWaitMs) {
        while (true) {
            long prev = lastCall.get();
            long now = System.currentTimeMillis();
            long next = Math.max(now, prev + gapMs);
            long waitMs = next - now;
            if (waitMs > maxWaitMs) {
                throw new ClaudeBusyException(waitMs, maxWaitMs);
            }
            if (lastCall.compareAndSet(prev, next)) {
                if (waitMs > 0) {
                    try {
                        Thread.sleep(waitMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return;
            }
        }
    }
}
