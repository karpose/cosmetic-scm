package com.cosmetic.scm.domain;

/** 附件挂在哪类单据上 */
public enum OwnerType {
    BATCH("批次"),
    PURCHASE_ORDER("采购单"),
    MFG_ORDER("生产 / 委托单"),
    COMPLAINT("投诉"),
    RECALL("召回"),
    SALES_RETURN("退货单"),
    PARTNER("往来单位"),
    EQUIPMENT("设备"),
    SUPPLIER_STATEMENT("对账单"),
    COMPLIANCE_DOC("合规资料"),
    STABILITY_STUDY("稳定性试验"),
    QUALITY_SPEC("质量标准"),
    NPD_PROJECT("新品项目"),
    DOC_REVISION("受控文件修订"),
    CHANGE_REQUEST("变更"),
    LABEL_ARTWORK("标签设计稿"),
    ARTWORK_PROOF("包材打样"),
    PO_SHIPMENT_LINE("送货通知明细"),
    QSO_APPOINTMENT("质量安全负责人任职"),
    OEM_CONTRACT("委托生产合同"),
    OEM_SUPERVISION("受托生产监督");

    private final String label;

    OwnerType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
