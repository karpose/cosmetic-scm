package com.cosmetic.scm.domain;

/** 备案进度 */
public enum FilingStatus {
    SUBMITTED("已提交"),
    FILED("已备案 / 注册"),
    WITHDRAWN("已撤回");

    private final String label;

    FilingStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
