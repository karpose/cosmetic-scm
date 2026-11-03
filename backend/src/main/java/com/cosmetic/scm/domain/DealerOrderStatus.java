package com.cosmetic.scm.domain;

/** 经销商订货单状态 */
public enum DealerOrderStatus {
    SUBMITTED("待审核"),
    APPROVED("已发货"),
    REJECTED("已驳回"),
    CANCELLED("已撤回");

    private final String label;

    DealerOrderStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
