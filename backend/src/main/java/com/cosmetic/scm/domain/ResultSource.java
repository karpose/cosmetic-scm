package com.cosmetic.scm.domain;

/** 检验结果的来源 */
public enum ResultSource {
    TEST("自检"),
    COA("供方报告");

    private final String label;

    ResultSource(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
