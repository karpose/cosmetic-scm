package com.cosmetic.scm.domain;

/** 物料验收标准交给受托厂的方式 */
public enum SpecAckMethod {
    PORTAL("门户确认"),
    OFFLINE("线下交接");

    private final String label;

    SpecAckMethod(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
