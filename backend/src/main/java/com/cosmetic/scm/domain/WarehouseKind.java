package com.cosmetic.scm.domain;

/** 仓库类型 */
public enum WarehouseKind {
    OWN("自有仓"),
    OEM("代工厂寄存仓"),
    PLATFORM("平台仓"),
    THIRD_PARTY("第三方仓");

    private final String label;

    WarehouseKind(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
