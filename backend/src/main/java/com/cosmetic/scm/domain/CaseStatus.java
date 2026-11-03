package com.cosmetic.scm.domain;

/** 投诉、召回的处理状态 */
public enum CaseStatus {
    OPEN("处理中"),
    CLOSED("已关闭");

    private final String label;

    CaseStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
