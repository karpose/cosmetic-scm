package com.cosmetic.scm.domain;

/** 留样状态 */
public enum RetainStatus {
    HELD("在库"),
    USED_UP("已用完"),
    DESTROYED("已销毁");

    private final String label;

    RetainStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
