package com.cosmetic.scm.domain;

/** 受托厂回传的生产进度 */
public enum OemStage {
    CONFIRMED("已接单"),
    SCHEDULED("已排产"),
    PRODUCING("生产中"),
    TESTING("检验中"),
    NOTE("备注");

    private final String label;

    OemStage(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
