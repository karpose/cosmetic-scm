package com.cosmetic.scm.domain;

/** 温湿度记录的来源。 */
public enum EnvSource {
    MANUAL("手工录入"),
    IMPORT("记录仪导入");

    private final String label;

    EnvSource(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
