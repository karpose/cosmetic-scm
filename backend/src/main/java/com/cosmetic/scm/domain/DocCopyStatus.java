package com.cosmetic.scm.domain;

/** 受控文件纸质副本的状态 */
public enum DocCopyStatus {
    ISSUED("在用"),
    RETRIEVED("已回收"),
    LOST("遗失");

    private final String label;

    DocCopyStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
