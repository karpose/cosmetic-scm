package com.cosmetic.scm.domain;

/** 产品合规资料的类型 */
public enum ComplianceDocType {
    SAFETY("安全评估"),
    EFFICACY("功效宣称依据"),
    LABEL("标签");

    private final String label;

    ComplianceDocType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
