package com.cosmetic.scm.domain;

/** 按判定原则得出的自查结论 */
public enum SelfCheckConclusion {
    NO_DEFECT("未发现缺陷"),
    DEFECT("存在缺陷"),
    SEVERE("存在严重缺陷");

    private final String label;

    SelfCheckConclusion(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
