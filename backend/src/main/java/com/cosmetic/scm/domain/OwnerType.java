package com.cosmetic.scm.domain;

/** 附件挂在哪类单据上 */
public enum OwnerType {
    BATCH("批次"),
    PURCHASE_ORDER("采购单"),
    MFG_ORDER("生产 / 委托单"),
    COMPLAINT("投诉"),
    RECALL("召回"),
    SALES_RETURN("退货单"),
    PARTNER("往来单位");

    private final String label;

    OwnerType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
