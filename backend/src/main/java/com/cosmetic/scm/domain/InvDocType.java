package com.cosmetic.scm.domain;

/** 库存作业单类型 */
public enum InvDocType {
    TRANSFER("仓间调拨"),
    ADJUST("盘点调整"),
    SCRAP("报废出库");

    private final String label;

    InvDocType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
