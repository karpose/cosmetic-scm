package com.cosmetic.scm.domain;

/** 配方版本状态 */
public enum FormulaStatus {
    DRAFT("草稿"),
    PENDING("待签批"),
    EFFECTIVE("生效中"),
    RETIRED("已停用"),
    DISCARDED("已作废");

    private final String label;

    FormulaStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
