package com.cosmetic.scm.domain;

/** 协助履职授权的状态 */
public enum DelegationStatus {
    ACTIVE("有效"),
    REVOKED("已撤销");

    private final String label;

    DelegationStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
