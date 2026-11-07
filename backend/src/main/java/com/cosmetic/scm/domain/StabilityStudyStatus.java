package com.cosmetic.scm.domain;

/** 稳定性试验的状态 */
public enum StabilityStudyStatus {
    ACTIVE("进行中"),
    REVIEW("结论待签批"),
    APPROVED("已签批"),
    CANCELLED("已取消"),
    VOID("已作废");

    private final String label;

    StabilityStudyStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
