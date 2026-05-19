package com.simon.rag.comm.enums;

public enum PromptKey {

    SYSTEM_PROMPT("system_prompt"),

    TYPE_HINT_FACTUAL("type_hint_factual"),
    TYPE_HINT_TECHNICAL("type_hint_technical"),
    TYPE_HINT_STRATEGIC("type_hint_strategic"),
    TYPE_HINT_BEHAVIORAL("type_hint_behavioral"),
    TYPE_HINT_DEFAULT("type_hint_default"),

    CONTEXTUAL_RETRIEVAL_PREFIX("contextual_retrieval_prefix");

    public final String key;

    PromptKey(String key) { this.key = key; }
}
