package com.cosmetic.scm.domain;

/** 投诉类别 */
public enum ComplaintCategory {
    ADVERSE("不良反应"),
    QUALITY("质量问题"),
    PACKAGE("包装 / 标签"),
    LOGISTICS("物流 / 破损"),
    OTHER("其他");

    private final String label;

    ComplaintCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
