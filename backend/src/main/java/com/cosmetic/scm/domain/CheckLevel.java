package com.cosmetic.scm.domain;

/** 检查要点的项目类别 */
public enum CheckLevel {
    KEY("关键项目"),
    IMPORTANT("其他重点项目"),
    GENERAL("一般项目");

    private final String label;

    CheckLevel(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
