package com.cosmetic.scm.domain;

/** 退货给供应商的状态。 */
public enum SupplierReturnStatus {
    PENDING("待质量签批"),
    DONE("已退回"),
    CANCELLED("已取消");

    private final String label;

    SupplierReturnStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
