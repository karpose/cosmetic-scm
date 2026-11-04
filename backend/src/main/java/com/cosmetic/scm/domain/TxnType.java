package com.cosmetic.scm.domain;

/** 库存流水类型 */
public enum TxnType {
    RECEIPT("采购入库"),
    ISSUE("生产发料"),
    PRODUCE("完工入库"),
    SHIP("销售出库"),
    TRANSFER("仓间调拨"),
    ADJUST("盘点调整"),
    SCRAP("报废出库"),
    RETURN_IN("退货入库"),
    RESTOCK("退货转可售"),
    ISSUE_BACK("发料退回");

    private final String label;

    TxnType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
