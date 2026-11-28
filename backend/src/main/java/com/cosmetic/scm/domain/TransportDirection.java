package com.cosmetic.scm.domain;

/** 运输记录的方向：销售发运（出库单）、受托厂成品到仓（委托单）。 */
public enum TransportDirection {
    OUTBOUND("销售发运"),
    INBOUND("受托厂成品到仓");

    private final String label;

    TransportDirection(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
