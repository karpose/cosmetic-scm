package com.cosmetic.scm.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 外部财务系统应付凭证同步状态。 */
@Getter
@RequiredArgsConstructor
public enum FinanceSyncStatus {

    PENDING("待同步"),
    SYNCED("已制证"),
    FAILED("同步失败");

    private final String label;
}
