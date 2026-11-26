package com.cosmetic.scm.domain;

/** 贮存区域的类型：留样区的记录用于自查第 16 项，其余用于第 20 项。 */
public enum StorageAreaKind {
    WAREHOUSE("仓储区"),
    RETAIN("留样区"),
    OTHER("其他");

    private final String label;

    StorageAreaKind(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
