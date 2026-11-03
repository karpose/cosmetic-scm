package com.cosmetic.scm.domain;

/** 签批结论 */
public enum Decision {
    RELEASED("放行"),
    REJECTED("不放行");

    private final String label;

    Decision(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
