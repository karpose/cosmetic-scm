package com.cosmetic.scm.domain;

/** 质量安全负责人所学专业类别（规范第七条列举的化妆品质量安全相关专业） */
public enum MajorField {
    COSMETICS("化妆品"),
    CHEMISTRY("化学"),
    CHEMICAL_ENG("化工"),
    BIOLOGY("生物"),
    MEDICINE("医学"),
    PHARMACY("药学"),
    FOOD("食品"),
    PUBLIC_HEALTH("公共卫生"),
    LAW("法学"),
    OTHER("其他相关专业");

    private final String label;

    MajorField(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
