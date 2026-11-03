package com.cosmetic.scm.domain;

/** 电商订单处理状态 */
public enum ChannelOrderStatus {
    RECEIVED("已接收"),
    SHIPPED("已出库"),
    FAILED("出库失败"),
    CANCELLED("已取消");

    private final String label;

    ChannelOrderStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
