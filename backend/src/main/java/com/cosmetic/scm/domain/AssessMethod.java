package com.cosmetic.scm.domain;

/** 受控文件培训考核的方式 */
public enum AssessMethod {
    WRITTEN("笔试"),
    ORAL("口头提问"),
    PRACTICAL("现场操作");

    private final String label;

    AssessMethod(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
