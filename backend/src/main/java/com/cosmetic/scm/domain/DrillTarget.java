package com.cosmetic.scm.domain;

/** 召回演练要联系的对象 */
public enum DrillTarget {
    CUSTOMER("收货客户"),
    FACTORY("受托生产企业"),
    WAREHOUSE("仓库");

    private final String label;

    DrillTarget(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
