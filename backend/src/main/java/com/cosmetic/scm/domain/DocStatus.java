package com.cosmetic.scm.domain;

/** 受控文件的状态 */
public enum DocStatus {
    ACTIVE("在用"),
    OBSOLETE("已作废");

    private final String label;

    DocStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
