package com.cosmetic.scm.domain;

/** 受托生产监督覆盖的环节（检查要点第 9 项：生产过程、检验、放行等） */
public enum SupervisionScope {
    MATERIAL("物料接收和使用"),
    PROCESS("生产过程"),
    ENVIRONMENT("厂房设备和卫生"),
    TESTING("检验"),
    RELEASE("出厂放行"),
    RECORDS("生产质量记录");

    private final String label;

    SupervisionScope(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
