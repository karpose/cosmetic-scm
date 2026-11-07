package com.cosmetic.scm.domain;

/** 批次检验记录的状态 */
public enum InspectionStatus {
    VALID("有效"),
    VOID("已作废");

    private final String label;

    InspectionStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
