package com.cosmetic.scm.domain;

/** 受托生产监督记录的状态 */
public enum SupervisionStatus {
    DRAFT("登记中"),
    SUBMITTED("待审核"),
    CONFIRMED("已审核"),
    CANCELLED("已作废");

    private final String label;

    SupervisionStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
