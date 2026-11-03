package com.cosmetic.scm.domain;

/** 系统角色 */
public enum Role {
    ADMIN("系统管理员"),
    MASTER_DATA("主数据管理"),
    PURCHASE("采购"),
    WAREHOUSE("仓库"),
    PRODUCTION("生产计划"),
    QA("质量检验"),
    QSO("质量安全负责人"),
    SALES("销售"),
    VIEWER("只读");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
