package com.cosmetic.scm.domain;

/** 供应商对账单状态 */
public enum StatementStatus {
    SENT("待供应商确认"),
    DISPUTED("供应商有异议"),
    CONFIRMED("待登记发票"),
    INVOICED("待财务审批"),
    APPROVED("待付款"),
    PAID("已付清"),
    CANCELLED("已作废");

    private final String label;

    StatementStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
