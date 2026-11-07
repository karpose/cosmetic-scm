package com.cosmetic.scm.domain;

/** 合规资料的状态 */
public enum ComplianceDocStatus {
    PENDING("待签批"),
    APPROVED("已签批"),
    REJECTED("已退回"),
    VOID("已作废");

    private final String label;

    ComplianceDocStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
