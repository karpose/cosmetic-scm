package com.cosmetic.scm.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import com.cosmetic.scm.domain.ContractSupply;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.SupplyMode;
import com.cosmetic.scm.domain.rules.ContractCoverage.Line;
import com.cosmetic.scm.domain.rules.ContractCoverage.Term;

import org.junit.jupiter.api.Test;

class ContractCoverageTest {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 12, 31);

    private static Term term(long id, boolean approved, LocalDate to, LocalDateTime terminatedAt) {
        return new Term(id, "HT-" + id, 7L, Set.of(100L, 101L), approved, FROM, to, terminatedAt);
    }

    @Test
    void coversByFactoryProductDateAndTermination() {
        Term t = term(1, true, TO, null);
        assertThat(t.covers(7L, 100L, LocalDateTime.of(2026, 1, 1, 0, 0))).isTrue();
        assertThat(t.covers(7L, 100L, LocalDateTime.of(2026, 12, 31, 23, 59))).isTrue();
        assertThat(t.covers(7L, 100L, LocalDateTime.of(2025, 12, 31, 23, 59))).isFalse();
        assertThat(t.covers(7L, 100L, LocalDateTime.of(2027, 1, 1, 0, 0))).isFalse();
        assertThat(t.covers(8L, 100L, LocalDateTime.of(2026, 6, 1, 8, 0))).isFalse();
        assertThat(t.covers(7L, 102L, LocalDateTime.of(2026, 6, 1, 8, 0))).isFalse();
        assertThat(t.covers(7L, 100L, null)).isFalse();
        // 没签批的不算
        assertThat(term(2, false, TO, null).covers(7L, 100L, LocalDateTime.of(2026, 6, 1, 8, 0))).isFalse();
        // 终止前下的单有依据，之后的没有；时间按整秒记，终止那一秒内下的单算终止前
        Term ended = term(3, true, TO, LocalDateTime.of(2026, 6, 1, 10, 0));
        assertThat(ended.covers(7L, 100L, LocalDateTime.of(2026, 6, 1, 9, 59))).isTrue();
        assertThat(ended.covers(7L, 100L, LocalDateTime.of(2026, 6, 1, 10, 0))).isTrue();
        assertThat(ended.covers(7L, 100L, LocalDateTime.of(2026, 6, 1, 10, 0, 1))).isFalse();
        assertThat(ended.inForce(LocalDate.of(2026, 5, 31))).isTrue();
        assertThat(ended.inForce(LocalDate.of(2026, 6, 1))).isFalse();
        assertThat(t.inForce(TO)).isTrue();
        assertThat(t.inForce(TO.plusDays(1))).isFalse();
    }

    @Test
    void picksTheContractThatRunsLongest() {
        LocalDateTime at = LocalDateTime.of(2026, 3, 1, 9, 0);
        List<Term> terms = List.of(term(1, true, LocalDate.of(2026, 6, 30), null), term(2, true, TO, null), term(3, false, TO.plusYears(1), null));
        assertThat(ContractCoverage.covering(terms, 7L, 100L, at)).map(Term::id).contains(2L);
        assertThat(ContractCoverage.covering(terms, 7L, 999L, at)).isEmpty();
        assertThat(ContractCoverage.covering(List.of(), 7L, 100L, at)).isEmpty();
    }

    @Test
    void supplyMismatchesFollowTheAgreedSplit() {
        List<Line> bom = List.of(new Line("甘油", ItemType.RM, SupplyMode.OEM), new Line("烟酰胺", ItemType.RM, SupplyMode.BRAND),
                new Line("瓶", ItemType.PK, SupplyMode.BRAND), new Line("彩盒", ItemType.PK, SupplyMode.OEM));
        assertThat(ContractCoverage.supplyMismatches("精华液", ContractSupply.MIXED, ContractSupply.MIXED, bom)).isEmpty();
        List<String> rmBrand = ContractCoverage.supplyMismatches("精华液", ContractSupply.BRAND, ContractSupply.MIXED, bom);
        assertThat(rmBrand).containsExactly("精华液：甘油 在 BOM 里是工厂代采，合同约定原料委托方提供");
        List<String> pkOem = ContractCoverage.supplyMismatches("精华液", ContractSupply.MIXED, ContractSupply.OEM, bom);
        assertThat(pkOem).containsExactly("精华液：瓶 在 BOM 里是品牌供料，合同约定包材受托方采购");
        assertThat(ContractCoverage.supplyMismatches("精华液", ContractSupply.OEM, ContractSupply.BRAND, bom)).hasSize(2);
    }
}
