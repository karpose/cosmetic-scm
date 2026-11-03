package com.cosmetic.scm.domain;

/** 批次来源 */
public enum SourceType {
    PO("采购收货"),
    MO("生产完工");

    private final String label;

    SourceType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
