package com.cosmetic.scm.domain;

/** 清场阶段 */
public enum ClearancePhase {
    BEFORE("开工前清场"),
    AFTER("完工后清场");

    private final String label;

    ClearancePhase(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
