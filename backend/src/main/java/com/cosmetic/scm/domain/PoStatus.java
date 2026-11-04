package com.cosmetic.scm.domain;

/** 采购单状态 */
public enum PoStatus {
    OPEN("待收货"),
    PARTIAL("部分收货"),
    RECEIVED("已收货"),
    /** 部分收货后不再收余量 */
    CLOSED("已关闭"),
    /** 一次都没收货就作废 */
    CANCELLED("已取消");

    private final String label;

    PoStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
