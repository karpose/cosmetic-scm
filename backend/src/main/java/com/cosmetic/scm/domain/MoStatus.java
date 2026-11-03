package com.cosmetic.scm.domain;

/** 生产单状态 */
public enum MoStatus {
    PLANNED("已计划"),
    ISSUED("已发料"),
    COMPLETED("已完工");

    private final String label;

    MoStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
