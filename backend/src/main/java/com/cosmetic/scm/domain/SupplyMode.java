package com.cosmetic.scm.domain;

/** 供料方式 */
public enum SupplyMode {
    BRAND("品牌供料"),
    OEM("工厂代采");

    private final String label;

    SupplyMode(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
