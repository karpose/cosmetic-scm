package com.cosmetic.scm.domain;

/** 供应商审核类型 */
public enum AuditType {
    INITIAL("首次审核"),
    ROUTINE("定期审核"),
    FOR_CAUSE("有因审核");

    private final String label;

    AuditType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
