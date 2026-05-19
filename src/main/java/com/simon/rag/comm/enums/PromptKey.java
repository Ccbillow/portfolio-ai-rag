package com.simon.rag.comm.enums;

public enum PromptKey {

    // Main chat prompt injected every query
    SYSTEM_PROMPT("system_prompt"),

    // Type-specific hints selected by QuestionClassifier, injected into {{typeHint}}
    TYPE_HINT_FACTUAL("type_hint_factual"),       // how long / when / where / yes-no
    TYPE_HINT_TECHNICAL("type_hint_technical"),   // architecture / project / skills
    TYPE_HINT_STRATEGIC("type_hint_strategic"),   // salary / weakness / conflict
    TYPE_HINT_BEHAVIORAL("type_hint_behavioral"), // STAR / tell-me-about-a-time
    TYPE_HINT_DEFAULT("type_hint_default"),       // fallback for unclassified questions

    // Ingestion-time prompts (called during document upload, not per query)
    CONTEXTUAL_RETRIEVAL_PREFIX("contextual_retrieval_prefix"), // 1-2 sentence context prefix per chunk
    RAPTOR_DOCUMENT_SUMMARY("raptor_document_summary");         // document-level summary chunk

    public final String key;

    PromptKey(String key) { this.key = key; }
}
