package com.cosmetic.scm.domain;

/** 偏差等级 */
public enum DeviationLevel {
    MINOR("微小偏差"),
    MAJOR("重大偏差"),
    CRITICAL("严重偏差");

    private final String label;

    DeviationLevel(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
