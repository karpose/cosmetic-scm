package com.cosmetic.scm.domain;

/** 新品项目的状态 */
public enum NpdStatus {
    PROPOSED("待立项审批"),
    ACTIVE("开发中"),
    LAUNCH_REVIEW("上市待批"),
    LAUNCHED("已上市"),
    REJECTED("不予立项"),
    CANCELLED("已取消");

    private final String label;

    NpdStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
