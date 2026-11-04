package com.cosmetic.scm.domain;

/** CAPA 类型 */
public enum CapaType {
    CORRECTIVE("纠正措施"),
    PREVENTIVE("预防措施");

    private final String label;

    CapaType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
