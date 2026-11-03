package com.cosmetic.scm.domain;

/** 退货处置结论 */
public enum ReturnDisposition {
    PENDING("待处置"),
    RESTOCK("转可售"),
    SCRAP("报废");

    private final String label;

    ReturnDisposition(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
