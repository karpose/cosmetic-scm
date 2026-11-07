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
    VIEWER("只读"),
    FINANCE("财务"),
    /** 外部账号：受托生产企业，只能用代工厂门户，只看本厂的委托单 */
    OEM_PORTAL("代工厂门户"),
    /** 外部账号：经销商，只能用经销商门户，只看本单位的订单和召回通知 */
    DEALER("经销商门户"),
    /** 外部账号：物料供应商，只能用供应商门户，只看本单位的采购单和自己传的文件。 */
    SUPPLIER_PORTAL("供应商门户");

    private final String label;

    Role(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public boolean isPortal() {
        return this == OEM_PORTAL || this == DEALER || this == SUPPLIER_PORTAL;
    }
}
