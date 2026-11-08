package com.cosmetic.scm.domain;

/** 标签设计稿的状态 */
public enum ArtworkStatus {
    PENDING("待签批"),
    APPROVED("已批准"),
    REJECTED("已退回"),
    SUPERSEDED("已替代");

    private final String label;

    ArtworkStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
