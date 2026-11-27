package com.cosmetic.scm.oemsupply;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.cosmetic.scm.domain.MoStatus;
import com.cosmetic.scm.domain.MoType;
import com.cosmetic.scm.domain.SpecStatus;
import com.cosmetic.scm.domain.SupplyMode;
import com.cosmetic.scm.domain.rules.SupplySpecRule;
import com.cosmetic.scm.master.Bom;
import com.cosmetic.scm.master.BomLine;
import com.cosmetic.scm.master.BomLineRepository;
import com.cosmetic.scm.master.BomRepository;
import com.cosmetic.scm.plm.FormulaLine;
import com.cosmetic.scm.plm.FormulaLineRepository;
import com.cosmetic.scm.production.MfgOrder;
import com.cosmetic.scm.quality.QualitySpec;
import com.cosmetic.scm.quality.QualitySpecRepository;

/**
 * 委托单里品牌供料的物料：已发料的单按锁定的配方版本读，没发料的按当前 BOM。
 * 供料验收标准页、代工厂门户、总览和 GMP 自查共用，只依赖仓库接口（服务之间不互相注入）。都是批量查询。
 */
public final class SuppliedMaterials {

    /** 一家受托厂要用到的一个品牌供料物料，以及用到它的委托单（按单号顺序）。 */
    public record Need(Long factoryId, Long itemId, List<MfgOrder> orders) {
    }

    private SuppliedMaterials() {
    }

    public static List<Need> of(Collection<MfgOrder> orders, FormulaLineRepository formulaLines, BomRepository boms, BomLineRepository bomLines) {
        List<MfgOrder> oem = orders.stream().filter(m -> m.getMoType() == MoType.OEM && m.getStatus() != MoStatus.CANCELLED)
                .sorted(Comparator.comparing(MfgOrder::getId)).toList();
        if (oem.isEmpty()) {
            return List.of();
        }
        Set<Long> versionIds = oem.stream().map(MfgOrder::getFormulaVersionId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Set<Long>> byVersion = new HashMap<>();
        if (!versionIds.isEmpty()) {
            formulaLines.findByVersionIdIn(versionIds).stream().filter(l -> l.getSupplyMode() == SupplyMode.BRAND)
                    .sorted(Comparator.comparing(FormulaLine::getLineNo))
                    .forEach(l -> byVersion.computeIfAbsent(l.getVersionId(), k -> new LinkedHashSet<>()).add(l.getItemId()));
        }
        Set<Long> fgWithoutVersion = oem.stream().filter(m -> m.getFormulaVersionId() == null).map(MfgOrder::getFgItemId).collect(Collectors.toSet());
        Map<Long, Set<Long>> byFg = new HashMap<>();
        if (!fgWithoutVersion.isEmpty()) {
            Map<Long, Long> fgOfBom = boms.findByFgItemIdIn(fgWithoutVersion).stream().collect(Collectors.toMap(Bom::getId, Bom::getFgItemId));
            if (!fgOfBom.isEmpty()) {
                for (BomLine l : bomLines.findByBomIdInOrderByBomIdAscLineNoAsc(fgOfBom.keySet())) {
                    if (l.getSupplyMode() == SupplyMode.BRAND) {
                        byFg.computeIfAbsent(fgOfBom.get(l.getBomId()), k -> new LinkedHashSet<>()).add(l.getItemId());
                    }
                }
            }
        }
        Map<String, Need> out = new LinkedHashMap<>();
        for (MfgOrder m : oem) {
            Set<Long> mats = m.getFormulaVersionId() != null ? byVersion.getOrDefault(m.getFormulaVersionId(), Set.of())
                    : byFg.getOrDefault(m.getFgItemId(), Set.of());
            for (Long itemId : mats) {
                out.computeIfAbsent(m.getFactoryId() + ":" + itemId, k -> new Need(m.getFactoryId(), itemId, new ArrayList<>())).orders().add(m);
            }
        }
        return List.copyOf(out.values());
    }

    /** 每个物料的现行（生效）标准。 */
    public static Map<Long, QualitySpec> current(Collection<QualitySpec> specsOfItems) {
        Map<Long, QualitySpec> out = new HashMap<>();
        specsOfItems.stream().filter(s -> s.getStatus() == SpecStatus.EFFECTIVE).forEach(s -> out.put(s.getItemId(), s));
        return out;
    }

    /** 每个物料在 at 这一刻生效的标准：at 之前签批过（现在生效或已替代）的最高版本。 */
    public static Map<Long, QualitySpec> effectiveAt(Collection<QualitySpec> specsOfItems, LocalDateTime at) {
        Map<Long, QualitySpec> out = new HashMap<>();
        for (QualitySpec s : specsOfItems) {
            if ((s.getStatus() != SpecStatus.EFFECTIVE && s.getStatus() != SpecStatus.SUPERSEDED) || s.getApprovedAt() == null || !s.getApprovedAt().isBefore(at)) {
                continue;
            }
            out.merge(s.getItemId(), s, (a, b) -> a.getVersionNo() >= b.getVersionNo() ? a : b);
        }
        return out;
    }

    /**
     * 这家受托厂对这些物料还没确认现行验收标准的（没有标准的也算），物料 id → 状态，按传入顺序。
     * 开关 scm.oem.require-spec-ack-for-issue 打开后，委托单首次发料前用它核对。
     */
    public static Map<Long, SupplySpecRule.State> unconfirmed(Long factoryId, Collection<Long> itemIds, QualitySpecRepository specs, OemSpecAckRepository acks) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, QualitySpec> cur = current(specs.findByItemIdIn(itemIds));
        Map<Long, List<Integer>> acked = acks.findByFactoryIdInAndItemIdIn(List.of(factoryId), itemIds).stream()
                .collect(Collectors.groupingBy(OemSpecAck::getItemId, Collectors.mapping(OemSpecAck::getSpecVersion, Collectors.toList())));
        Map<Long, SupplySpecRule.State> out = new LinkedHashMap<>();
        for (Long id : itemIds) {
            QualitySpec s = cur.get(id);
            SupplySpecRule.State st = SupplySpecRule.state(s == null ? null : s.getVersionNo(), acked.getOrDefault(id, List.of()));
            if (st != SupplySpecRule.State.ACKED) {
                out.put(id, st);
            }
        }
        return out;
    }

    public static String key(Long factoryId, Long itemId) {
        return factoryId + ":" + itemId;
    }
}
