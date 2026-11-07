package com.cosmetic.scm.domain;

/** 质量标准版本的状态 */
public enum SpecStatus {
    PENDING("待签批"),
    EFFECTIVE("生效"),
    SUPERSEDED("已替代"),
    REJECTED("已退回");

    private final String label;

    SpecStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
