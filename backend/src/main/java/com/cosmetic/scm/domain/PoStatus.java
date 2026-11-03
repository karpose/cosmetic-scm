package com.cosmetic.scm.domain;

/** 采购单状态 */
public enum PoStatus {
    OPEN("待收货"),
    PARTIAL("部分收货"),
    RECEIVED("已收货");

    private final String label;

    PoStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
