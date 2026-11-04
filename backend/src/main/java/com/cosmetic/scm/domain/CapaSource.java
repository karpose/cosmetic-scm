package com.cosmetic.scm.domain;

/** CAPA 来源 */
public enum CapaSource {
    DEVIATION("偏差"),
    COMPLAINT("投诉"),
    RECALL("召回"),
    AUDIT("供应商审核"),
    OTHER("其他");

    private final String label;

    CapaSource(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
