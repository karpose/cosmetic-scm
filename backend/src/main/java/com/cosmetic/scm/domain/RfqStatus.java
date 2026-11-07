package com.cosmetic.scm.domain;

/** 询价单状态。OPEN 过了截止日也算可以开标，看 RfqService.sealed */
public enum RfqStatus {
    OPEN("询价中"),
    CLOSED("已开标"),
    AWARDED("已定标"),
    CANCELLED("已取消");

    private final String label;

    RfqStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
