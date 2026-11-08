package com.cosmetic.scm.domain;

/** 稳定性结果更正的状态 */
public enum CorrectionStatus {
    PENDING("待审批"),
    APPROVED("已更正"),
    REJECTED("已驳回");

    private final String label;

    CorrectionStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
