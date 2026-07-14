package com.simon.rag.comm.constant;

public final class CacheConstant {

    private CacheConstant() {}

    /** Document ingestion task status (TTL 24h) */
    public static final String INGEST_TASK_PREFIX = "rag:ingest:task:";

    /** TTL (hours) for ingestion task status keys written on upload/retry. */
    public static final long INGEST_STATUS_TTL_HOURS = 24;

    /** Chat answer cache — keyed by MD5(question), disabled until production */
    public static final String CHAT_CACHE_PREFIX = "rag:chat:cache:";

    /** Conversation history per sessionId — Redis List, max 2 turns, TTL 60 min */
    public static final String CONVERSATION_HISTORY_PREFIX = "rag:chat:history:";
}
