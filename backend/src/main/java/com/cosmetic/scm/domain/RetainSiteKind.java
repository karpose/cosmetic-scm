package com.cosmetic.scm.domain;

/** 留样地点的类型（GMP 第五十六条）。只有“其他经营场所”要在首次留样之日起 20 个工作日内报告。 */
public enum RetainSiteKind {
    DOMICILE("住所"),
    MAIN_PREMISES("主要经营场所"),
    OTHER_PREMISES("其他经营场所");

    private final String label;

    RetainSiteKind(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
