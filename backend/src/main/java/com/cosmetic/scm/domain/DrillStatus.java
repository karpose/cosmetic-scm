package com.cosmetic.scm.domain;

/** 召回演练的状态。列宽 12。 */
public enum DrillStatus {
    IN_PROGRESS("进行中"),
    SUBMITTED("待审核"),
    REVIEWED("已审核"),
    CANCELLED("已作废");

    private final String label;

    DrillStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
