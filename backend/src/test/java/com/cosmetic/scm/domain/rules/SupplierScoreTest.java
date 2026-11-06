package com.cosmetic.scm.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.cosmetic.scm.domain.rules.SupplierScore.AuditLevel;

import org.junit.jupiter.api.Test;

class SupplierScoreTest {

    @Test
    void allThreePartsUseTheWeights() {
        // 质量 9/10 × 50 = 45，交货 3/4 × 30 = 22.5，审核合格 20，合计 87.5，四舍五入 88
        assertThat(SupplierScore.of(10, 1, 4, 3, AuditLevel.PASS)).isEqualTo(new SupplierScore.Result(88, "B"));
        assertThat(SupplierScore.of(10, 0, 4, 4, AuditLevel.PASS)).isEqualTo(new SupplierScore.Result(100, "A"));
        // 有条件合格 0.6 × 20 = 12：50 + 30 + 12 = 92
        assertThat(SupplierScore.of(10, 0, 4, 4, AuditLevel.CONDITIONAL)).isEqualTo(new SupplierScore.Result(92, "A"));
    }

    @Test
    void missingPartsAreLeftOutNotCountedAsZero() {
        // 只有质量：9/10，按 50 分放大后是 90 分。新供应商不能因为没交过货就得低分
        assertThat(SupplierScore.of(10, 1, 0, 0, null)).isEqualTo(new SupplierScore.Result(90, "A"));
        // 只有交货：1/2
        assertThat(SupplierScore.of(0, 0, 2, 1, null)).isEqualTo(new SupplierScore.Result(50, "D"));
        assertThat(SupplierScore.of(0, 0, 0, 0, null)).isNull();
    }

    @Test
    void gradeBoundaries() {
        assertThat(SupplierScore.of(10, 1, 0, 0, null).grade()).isEqualTo("A"); // 90
        assertThat(SupplierScore.of(100, 11, 0, 0, null).grade()).isEqualTo("B"); // 89
        assertThat(SupplierScore.of(4, 1, 0, 0, null).grade()).isEqualTo("B"); // 75
        assertThat(SupplierScore.of(100, 26, 0, 0, null).grade()).isEqualTo("C"); // 74
        assertThat(SupplierScore.of(10, 4, 0, 0, null).grade()).isEqualTo("C"); // 60
        assertThat(SupplierScore.of(100, 41, 0, 0, null).grade()).isEqualTo("D"); // 59
        assertThat(SupplierScore.of(0, 0, 0, 0, AuditLevel.FAIL)).isEqualTo(new SupplierScore.Result(0, "D"));
    }
}
