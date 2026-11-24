package com.cosmetic.scm.domain;

import java.util.List;

/**
 * 《化妆品生产质量管理规范检查要点（委托生产版）》要求委托方建立并执行的制度。items：对应的检查项目序号；
 * optional：只在特定情况下需要（向受托方提供物料时）。清单是按检查要点整理的默认值，要改在这里改。
 */
public enum QualityProcedure {
    RESPONSIBILITY("质量安全责任制", List.of(3), false),
    QSO_DUTY("质量安全负责人履职和协助履职", List.of(4, 5), false),
    OEM_SELECTION("受托生产企业遴选和审核", List.of(6, 7), false),
    OEM_SUPERVISION("受托生产活动监督", List.of(9, 18), false),
    OEM_CHANGE("受托生产企业更换", List.of(10), false),
    FILING("注册备案管理", List.of(11), false),
    HEALTH("从业人员健康管理", List.of(11), false),
    TRAINING("培训", List.of(11), false),
    SELF_CHECK("质量管理体系自查", List.of(11), false),
    RELEASE("产品放行", List.of(11, 13), false),
    RETAIN("留样", List.of(11, 15, 16), false),
    SALES_RECORD("销售记录", List.of(11, 19), false),
    STORAGE("贮存和运输", List.of(11, 20), false),
    RETURN("退货", List.of(11, 21), false),
    COMPLAINT("质量投诉", List.of(11, 22), false),
    RECALL("召回", List.of(11, 24), false),
    ADVERSE("不良反应监测和评价", List.of(11, 23), false),
    RECORDS("记录管理", List.of(17), false),
    SUPPLIER_SELECTION("物料供应商遴选", List.of(12), true),
    MATERIAL_REVIEW("物料审查", List.of(12), true),
    INCOMING_CHECK("物料进货查验", List.of(12), true),
    MATERIAL_RELEASE("物料放行", List.of(12), true);

    private final String label;
    private final List<Integer> items;
    private final boolean optional;

    QualityProcedure(String label, List<Integer> items, boolean optional) {
        this.label = label;
        this.items = items;
        this.optional = optional;
    }

    public String getLabel() {
        return label;
    }

    public List<Integer> getItems() {
        return items;
    }

    public boolean isOptional() {
        return optional;
    }
}
