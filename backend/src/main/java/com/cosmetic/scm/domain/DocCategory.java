package com.cosmetic.scm.domain;

/** 受控文件的类别 */
public enum DocCategory {
    SOP("标准操作规程"),
    PROCESS("工艺规程"),
    FORM("记录表格"),
    MANUAL("手册"),
    OTHER("其他");

    private final String label;

    DocCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
