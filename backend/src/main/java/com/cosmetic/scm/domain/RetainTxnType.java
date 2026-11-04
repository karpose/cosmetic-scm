package com.cosmetic.scm.domain;

/** 留样变动 */
public enum RetainTxnType {
    USE("领用"),
    DESTROY("销毁");

    private final String label;

    RetainTxnType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
