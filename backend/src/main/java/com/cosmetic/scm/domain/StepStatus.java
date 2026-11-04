package com.cosmetic.scm.domain;

/** 批记录工序状态 */
public enum StepStatus {
    PENDING("待执行"),
    DONE("已执行"),
    REVIEWED("已复核");

    private final String label;

    StepStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
