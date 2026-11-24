package com.cosmetic.scm.domain;

/** GMP 自查的状态 */
public enum SelfCheckStatus {
    DRAFT("自查中"),
    SUBMITTED("待签批"),
    APPROVED("已签批"),
    CANCELLED("已作废");

    private final String label;

    SelfCheckStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
