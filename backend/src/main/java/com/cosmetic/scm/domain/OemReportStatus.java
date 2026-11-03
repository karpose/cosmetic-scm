package com.cosmetic.scm.domain;

/** 受托厂完工申报状态 */
public enum OemReportStatus {
    SUBMITTED("待品牌方确认"),
    ACCEPTED("已采纳入库"),
    RETURNED("已退回");

    private final String label;

    OemReportStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
