package com.cosmetic.scm.domain;

/** 监督结论，按发现的问题算 */
public enum SupervisionConclusion {
    NONE("未发现问题"),
    MINOR("发现一般问题"),
    MAJOR("发现重大问题");

    private final String label;

    SupervisionConclusion(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
