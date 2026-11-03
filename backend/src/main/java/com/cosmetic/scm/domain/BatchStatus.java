package com.cosmetic.scm.domain;

/** 批次质量状态 */
public enum BatchStatus {
    QUARANTINE("待检"),
    PENDING_RELEASE("待上市放行"),
    RELEASED("已放行"),
    REJECTED("不合格"),
    FROZEN("已冻结");

    private final String label;

    BatchStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
