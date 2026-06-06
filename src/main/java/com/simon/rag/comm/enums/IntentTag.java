package com.simon.rag.comm.enums;

import com.fasterxml.jackson.annotation.JsonValue;

public enum IntentTag {
    ARCHITECTURE("architecture"),
    PRODUCTION_ISSUE("production_issue"),
    LEADERSHIP("leadership"),
    DELIVERY("delivery"),
    TECHNICAL_DEPTH("technical_depth"),
    AMBIGUITY("ambiguity"),
    CONFLICT("conflict"),
    GROWTH("growth");

    private final String value;
    IntentTag(String value) { this.value = value; }

    @JsonValue
    public String getValue() { return value; }

    public static IntentTag fromValue(String v) {
        for (IntentTag t : values())
            if (t.value.equalsIgnoreCase(v)) return t;
        throw new IllegalArgumentException("Unknown IntentTag: " + v);
    }
}
