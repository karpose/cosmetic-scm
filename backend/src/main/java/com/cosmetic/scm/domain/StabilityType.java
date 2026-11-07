package com.cosmetic.scm.domain;

/** 稳定性试验的类型 */
public enum StabilityType {
    ACCELERATED("加速"),
    LONG_TERM("长期"),
    CYCLE("循环"),
    LIGHT("光照"),
    OTHER("其他");

    private final String label;

    StabilityType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
