package com.cosmetic.scm.domain;

/** 文件修订版本的状态 */
public enum RevisionStatus {
    PENDING("待签批"),
    APPROVED("已签批"),
    REJECTED("已退回");

    private final String label;

    RevisionStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
