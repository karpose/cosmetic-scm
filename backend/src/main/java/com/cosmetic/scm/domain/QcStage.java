package com.cosmetic.scm.domain;

/** 质量签批环节 */
public enum QcStage {
    MATERIAL_QC("物料检验放行"),
    MARKET_RELEASE("成品上市放行");

    private final String label;

    QcStage(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
