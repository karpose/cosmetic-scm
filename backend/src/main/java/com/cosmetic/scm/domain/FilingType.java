package com.cosmetic.scm.domain;

/** 备案类型 */
public enum FilingType {
    GENERAL("普通化妆品备案"),
    SPECIAL("特殊化妆品注册");

    private final String label;

    FilingType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
