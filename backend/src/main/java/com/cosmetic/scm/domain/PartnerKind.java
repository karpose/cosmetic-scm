package com.cosmetic.scm.domain;

/** 往来单位类型 */
public enum PartnerKind {
    SUPPLIER("物料供应商"),
    OEM("受托生产企业"),
    OWN_FACTORY("自有工厂"),
    CUSTOMER("客户渠道");

    private final String label;

    PartnerKind(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
