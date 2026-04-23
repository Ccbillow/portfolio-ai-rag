package com.simon.rag.comm.constant;

public final class CacheConstant {

    private CacheConstant() {}

    /** Document ingestion task status (TTL 24h) */
    public static final String INGEST_TASK_PREFIX = "rag:ingest:task:";

    /** Chat answer cache — keyed by MD5(question), disabled until production */
    public static final String CHAT_CACHE_PREFIX = "rag:chat:cache:";
}
