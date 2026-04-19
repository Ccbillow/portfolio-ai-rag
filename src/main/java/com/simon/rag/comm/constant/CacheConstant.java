package com.simon.rag.comm.constant;

/**
 * Redis key prefixes — centralised to prevent typos and naming collisions.
 *
 * <p>Pattern: {module}:{entity}:{id_or_qualifier}
 */
public final class CacheConstant {

    private CacheConstant() {}

    /** Chat query cache — stores LLM answers for identical questions (TTL 1h) */
    public static final String CHAT_QUERY_PREFIX = "rag:chat:query:";

    /** Rate limiting — sliding window counters per IP (TTL 1min) */
    public static final String RATE_LIMIT_IP_PREFIX = "rag:rate:ip:";

    /** Rate limiting — per authenticated user token */
    public static final String RATE_LIMIT_USER_PREFIX = "rag:rate:user:";

    /** JWT blacklist — invalidated tokens (TTL = token remaining lifetime) */
    public static final String JWT_BLACKLIST_PREFIX = "rag:jwt:blacklist:";

    /** Document ingestion task status (TTL 24h) */
    public static final String INGEST_TASK_PREFIX = "rag:ingest:task:";
}