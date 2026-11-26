package com.cosmetic.scm.domain;

/** 受托生产监督的方式 */
public enum SupervisionMethod {
    ON_SITE("驻厂监督"),
    INSPECTION("现场检查"),
    RECORD_REVIEW("调阅记录"),
    REMOTE("远程视频");

    private final String label;

    SupervisionMethod(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
