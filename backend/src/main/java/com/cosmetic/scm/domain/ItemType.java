package com.cosmetic.scm.domain;

/** 物料类型 */
public enum ItemType {
    RM("原料"),
    PK("包材"),
    FG("成品");

    private final String label;

    ItemType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
