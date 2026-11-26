package com.cosmetic.scm.domain;

/** 召回演练里一个对象的联系结果 */
public enum DrillResult {
    REACHED("已联系上"),
    UNREACHABLE("联系不上");

    private final String label;

    DrillResult(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
