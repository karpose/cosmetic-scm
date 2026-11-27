package com.cosmetic.scm.domain;

/** 运输记录包含的单据类型（列宽 4）。 */
public enum TransportDocType {
    SO("销售出库单"),
    MO("委托单");

    private final String label;

    TransportDocType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
