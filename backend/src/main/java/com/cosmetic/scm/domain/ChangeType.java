package com.cosmetic.scm.domain;

/** 变更类型 */
public enum ChangeType {
    FORMULA("配方"),
    PROCESS("工艺"),
    MATERIAL("原料或供应商"),
    PACKAGE("包材"),
    LABEL("标签"),
    DOCUMENT("文件"),
    EQUIPMENT("设备"),
    OTHER("其他");

    private final String label;

    ChangeType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
