package com.cosmetic.scm.domain;

/** 试制类型 */
public enum TrialKind {
    LAB("小试"),
    PILOT("中试");

    private final String label;

    TrialKind(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
