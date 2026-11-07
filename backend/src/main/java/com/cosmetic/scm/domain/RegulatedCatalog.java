package com.cosmetic.scm.domain;

/** 法规目录的类别 */
public enum RegulatedCatalog {
    BANNED("禁用"),
    RESTRICTED("限用");

    private final String label;

    RegulatedCatalog(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
