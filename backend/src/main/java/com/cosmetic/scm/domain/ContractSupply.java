package com.cosmetic.scm.domain;

/** 委托生产合同约定的物料由谁采购（原料、包材分别约定）。 */
public enum ContractSupply {
    BRAND("委托方提供"),
    OEM("受托方采购"),
    MIXED("按 BOM 逐项约定");

    private final String label;

    ContractSupply(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
