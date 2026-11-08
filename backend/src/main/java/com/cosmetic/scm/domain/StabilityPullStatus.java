package com.cosmetic.scm.domain;

/** 稳定性取样的状态 */
public enum StabilityPullStatus {
    PENDING("待取样"),
    DONE("已检测"),
    SKIPPED("未取样"),
    CANCELLED("方案取消");

    private final String label;

    StabilityPullStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
