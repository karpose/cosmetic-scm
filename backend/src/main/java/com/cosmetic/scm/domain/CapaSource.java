package com.cosmetic.scm.domain;

/** CAPA 来源 */
public enum CapaSource {
    DEVIATION("偏差"),
    COMPLAINT("投诉"),
    RECALL("召回"),
    AUDIT("供应商审核"),
    SELF_CHECK("GMP 自查"),
    SUPERVISION("受托生产监督"),
    /** 召回演练没达标。列宽 12，名字不能再长 */
    RECALL_DRILL("召回演练"),
    OTHER("其他");

    private final String label;

    CapaSource(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
