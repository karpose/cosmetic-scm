package com.cosmetic.scm.domain;

/** 设备状态 */
public enum EquipmentStatus {
    IN_USE("在用"),
    STOPPED("停用");

    private final String label;

    EquipmentStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
