package com.cosmetic.scm.domain;

/** CAPA 状态 */
public enum CapaStatus {
    OPEN("进行中"),
    DONE("待验证"),
    VERIFIED("已验证");

    private final String label;

    CapaStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
