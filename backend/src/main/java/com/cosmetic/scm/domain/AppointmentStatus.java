package com.cosmetic.scm.domain;

/** 质量安全负责人任职的状态 */
public enum AppointmentStatus {
    DRAFT("待生效"),
    ACTIVE("现任"),
    ENDED("已离任");

    private final String label;

    AppointmentStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
