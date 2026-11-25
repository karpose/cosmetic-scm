package com.cosmetic.scm.domain;

/** 委托生产合同的状态。生效中的合同是否在期限内按日期算，不另存状态。 */
public enum ContractStatus {
    DRAFT("草稿"),
    PENDING("待签批"),
    ACTIVE("已生效"),
    TERMINATED("已终止"),
    VOID("已作废");

    private final String label;

    ContractStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
