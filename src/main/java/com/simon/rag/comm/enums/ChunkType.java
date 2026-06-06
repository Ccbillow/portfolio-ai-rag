package com.simon.rag.comm.enums;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ChunkType {
    INCIDENT("incident"),
    PROJECT("project"),
    DECISION("decision"),
    ACHIEVEMENT("achievement"),
    PROCESS("process"),
    BACKGROUND("background"),
    DOCUMENT_SUMMARY("document_summary");

    private final String value;
    ChunkType(String value) { this.value = value; }

    @JsonValue
    public String getValue() { return value; }

    public static ChunkType fromValue(String v) {
        for (ChunkType t : values())
            if (t.value.equalsIgnoreCase(v)) return t;
        throw new IllegalArgumentException("Unknown ChunkType: " + v);
    }
}
