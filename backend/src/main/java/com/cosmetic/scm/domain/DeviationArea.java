package com.cosmetic.scm.domain;

/** 偏差发生环节 */
public enum DeviationArea {
    PRODUCTION("生产"),
    QC("检验"),
    WAREHOUSE("仓储"),
    SUPPLIER("供应商 / 受托厂"),
    TRANSPORT("运输"),
    OTHER("其他");

    private final String label;

    DeviationArea(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
