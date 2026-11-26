package com.cosmetic.scm.domain;

/** 监督发现的问题的程度。重大问题要转 CAPA，一般问题可以当场纠正 */
public enum FindingSeverity {
    MINOR("一般问题"),
    MAJOR("重大问题");

    private final String label;

    FindingSeverity(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
