package com.cosmetic.scm.domain;

/** 包材打样的状态 */
public enum ProofStatus {
    PENDING("待签样"),
    APPROVED("已签样"),
    REJECTED("不合格"),
    SUPERSEDED("已替代"),
    VOID("已作废");

    private final String label;

    ProofStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
