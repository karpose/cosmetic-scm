package com.cosmetic.scm.domain;

/** 可以授权他人协助的质量安全负责人职责（按规范第七条第三至第五项整理，第一、二项不能授权） */
public enum QsoDuty {
    REVIEW("产品安全评估、配方、工艺、物料供应商、标签和注册备案资料的审核"),
    RELEASE("物料放行和产品放行"),
    ADVERSE("不良反应监测管理");

    private final String label;

    QsoDuty(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
