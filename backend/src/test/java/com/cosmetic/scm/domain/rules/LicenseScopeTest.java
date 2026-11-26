package com.cosmetic.scm.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class LicenseScopeTest {

    @Test
    void ordinaryProductsNeedNoMarking() {
        assertThat(LicenseScope.required(false, false)).isEmpty();
        assertThat(LicenseScope.missing(false, false, false, false)).isEmpty();
    }

    @Test
    void eachConditionIsCheckedOnItsOwn() {
        assertThat(LicenseScope.required(true, true)).containsExactly("眼部护肤类", "儿童护肤类");
        assertThat(LicenseScope.missing(true, false, false, false)).containsExactly("眼部护肤类");
        assertThat(LicenseScope.missing(true, false, true, false)).isEmpty();
        // 标了儿童护肤类不能代替眼部护肤类
        assertThat(LicenseScope.missing(true, false, false, true)).containsExactly("眼部护肤类");
        assertThat(LicenseScope.missing(true, true, true, false)).containsExactly("儿童护肤类");
        assertThat(LicenseScope.missing(true, true, false, false)).containsExactly("眼部护肤类", "儿童护肤类");
        // 许可证上多标注的不影响
        assertThat(LicenseScope.missing(false, false, true, true)).isEmpty();
    }

    @Test
    void messageNamesFactoryProductAndConditions() {
        assertThat(LicenseScope.message("禾川", "眼霜", List.of("眼部护肤类", "儿童护肤类"), "委托生产"))
                .startsWith("禾川 的生产许可项目没有标注具备眼部护肤类、儿童护肤类化妆品生产条件，不能委托生产 眼霜");
    }
}
