package com.cosmetic.scm.domain.rules;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.cosmetic.scm.domain.ContractSupply;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.SupplyMode;

/**
 * 委托生产合同的覆盖和约定核对（纯 Java）。
 * 一张委托单有没有合同依据，看下单那一刻：签批过的合同（生效中或已终止）、受托厂和成品对得上、下单日期在合同期限内、
 * 下单时还没终止。按下单时间判断，所以之后合同到期或终止不影响已下的单；先下单后补签的合同，期限覆盖下单日期的也算有依据。
 */
public final class ContractCoverage {

    /**
     * approved：签批过（生效中或已终止）；terminatedAt：终止时间，没终止为空。
     * 下单和终止的时间都按整秒记（MySQL 的 DATETIME 会把小数秒四舍五入），终止那一秒内下的单算终止前，
     * 这样下单时的判断和事后读库的判断一致。
     */
    public record Term(Long id, String contractNo, Long factoryId, Set<Long> products, boolean approved, LocalDate validFrom, LocalDate validTo,
                       LocalDateTime terminatedAt) {

        public boolean covers(Long factory, Long fgItemId, LocalDateTime at) {
            if (!approved || !factoryId.equals(factory) || !products.contains(fgItemId) || at == null) {
                return false;
            }
            LocalDate d = at.toLocalDate();
            return !d.isBefore(validFrom) && !d.isAfter(validTo) && (terminatedAt == null || !at.isAfter(terminatedAt));
        }

        /** 某天是否在执行中（签批过、期限内、那天还没终止）。 */
        public boolean inForce(LocalDate day) {
            return approved && !day.isBefore(validFrom) && !day.isAfter(validTo) && (terminatedAt == null || day.isBefore(terminatedAt.toLocalDate()));
        }
    }

    /** BOM 的一行：物料名称、类型、供料方式。 */
    public record Line(String itemName, ItemType itemType, SupplyMode mode) {
    }

    private ContractCoverage() {
    }

    /** 下单时覆盖这张单的合同；有多份时取到期最晚的。 */
    public static Optional<Term> covering(Collection<Term> terms, Long factoryId, Long fgItemId, LocalDateTime at) {
        return terms.stream().filter(t -> t.covers(factoryId, fgItemId, at)).max(Comparator.comparing(Term::validTo).thenComparing(Term::id));
    }

    /**
     * 合同约定和 BOM 不一致的地方：合同写原料（包材）全部由委托方提供，BOM 里却有工厂代采的，反过来也一样。
     * 约定“按 BOM 逐项约定”的不核对。成品自己不算。
     */
    public static List<String> supplyMismatches(String fgName, ContractSupply rm, ContractSupply pk, List<Line> bom) {
        List<String> out = new ArrayList<>();
        for (Line l : bom) {
            ContractSupply agreed = l.itemType() == ItemType.RM ? rm : l.itemType() == ItemType.PK ? pk : null;
            if (agreed == null || agreed == ContractSupply.MIXED || l.mode() == null) {
                continue;
            }
            SupplyMode expected = agreed == ContractSupply.BRAND ? SupplyMode.BRAND : SupplyMode.OEM;
            if (l.mode() != expected) {
                out.add(fgName + "：" + l.itemName() + " 在 BOM 里是" + l.mode().getLabel() + "，合同约定" + (l.itemType() == ItemType.RM ? "原料" : "包材")
                        + agreed.getLabel());
            }
        }
        return out;
    }
}
