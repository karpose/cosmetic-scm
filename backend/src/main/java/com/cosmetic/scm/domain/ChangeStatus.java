package com.cosmetic.scm.domain;

/** 变更的状态 */
public enum ChangeStatus {
    SUBMITTED("待评估"),
    ASSESSED("待批准"),
    APPROVED("实施中"),
    CLOSED("已关闭"),
    REJECTED("不批准"),
    CANCELLED("已取消");

    private final String label;

    ChangeStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
