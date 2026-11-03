package com.cosmetic.scm.domain;

/** 生产方式 */
public enum MoType {
    OWN("自产"),
    OEM("委托生产");

    private final String label;

    MoType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
