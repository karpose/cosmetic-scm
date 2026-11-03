package com.cosmetic.scm.domain;

/** 严重程度 */
public enum Severity {
    NORMAL("一般"),
    SERIOUS("严重");

    private final String label;

    Severity(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
