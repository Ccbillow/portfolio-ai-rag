package com.simon.rag.comm.enums;

import com.fasterxml.jackson.annotation.JsonValue;

public enum BehavioralTag {
    OWNERSHIP("ownership"),
    CROSS_TEAM_COMMUNICATION("cross_team_communication"),
    MENTORING("mentoring"),
    DATA_DRIVEN("data_driven"),
    PRIORITIZATION("prioritization"),
    STAKEHOLDER_MANAGEMENT("stakeholder_management"),
    RESILIENCE("resilience"),
    PROACTIVE("proactive");

    private final String value;
    BehavioralTag(String value) { this.value = value; }

    @JsonValue
    public String getValue() { return value; }

    public static BehavioralTag fromValue(String v) {
        for (BehavioralTag t : values())
            if (t.value.equalsIgnoreCase(v)) return t;
        throw new IllegalArgumentException("Unknown BehavioralTag: " + v);
    }
}
