package com.cosmetic.scm.domain;

/** 供应商送货通知的状态。 */
public enum ShipmentStatus {
    OPEN("待收货"),
    RECEIVED("已收货"),
    CLOSED("随订单关闭"),
    CANCELLED("已取消");

    private final String label;

    ShipmentStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
