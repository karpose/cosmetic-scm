package com.cosmetic.scm.oemcontract;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.cosmetic.scm.domain.ContractStatus;
import com.cosmetic.scm.domain.rules.ContractCoverage;
import com.cosmetic.scm.domain.rules.ContractCoverage.Term;
import com.cosmetic.scm.master.Bom;
import com.cosmetic.scm.master.BomLine;
import com.cosmetic.scm.master.BomLineRepository;
import com.cosmetic.scm.master.BomRepository;
import com.cosmetic.scm.master.Item;

/**
 * 从数据库读出合同条款，交给 {@link ContractCoverage} 判断。下委托单、GMP 自查、上线检查和合同页共用，
 * 只依赖仓库接口（服务之间不互相注入）。
 */
public final class OemContractTerms {

    /** 签批过的合同：生效中、已终止。终止前下的单仍有依据。 */
    public static final List<ContractStatus> APPROVED = List.of(ContractStatus.ACTIVE, ContractStatus.TERMINATED);

    private OemContractTerms() {
    }

    /** 这些受托厂签批过的全部合同。合同按受托厂查，一家通常只有几份。 */
    public static List<Term> ofFactories(OemContractRepository contracts, OemContractProductRepository products, Collection<Long> factoryIds) {
        if (factoryIds.isEmpty()) {
            return List.of();
        }
        return terms(contracts.findByFactoryIdInAndStatusIn(factoryIds, APPROVED), products);
    }

    /** 合同相关的时间（下委托单、终止）按整秒记：MySQL 的 DATETIME 会把小数秒四舍五入，不截断的话下单时的判断和事后读库的判断会不一样。 */
    public static LocalDateTime stamp(LocalDateTime t) {
        return t.truncatedTo(ChronoUnit.SECONDS);
    }

    /**
     * 下委托单时用：先只查期限内候选合同的 id，再加共享锁（和终止合同互斥）。调用方在这之后再取下单时间，
     * 这样终止先提交的，下单时间不早于终止时间；下单先提交的，终止时间不早于下单时间。
     */
    public static List<OemContract> lockCandidates(OemContractRepository contracts, Long factoryId, Long fgItemId, LocalDate day) {
        List<Long> ids = contracts.findIdsInTerm(factoryId, fgItemId, day, APPROVED);
        return ids.isEmpty() ? List.of() : contracts.lockSharedByIdIn(ids);
    }

    /** 在 at 这一刻覆盖“受托厂 + 成品”的合同。只查期限内的几行。 */
    public static Optional<Term> covering(OemContractRepository contracts, OemContractProductRepository products, Long factoryId, Long fgItemId,
                                          LocalDateTime at) {
        List<OemContract> list = contracts.findInTerm(factoryId, fgItemId, at.toLocalDate(), APPROVED);
        return ContractCoverage.covering(terms(list, products), factoryId, fgItemId, at);
    }

    public static List<Term> terms(List<OemContract> list, OemContractProductRepository products) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, Set<Long>> byContract = products.findByContractIdIn(list.stream().map(OemContract::getId).toList()).stream()
                .collect(Collectors.groupingBy(OemContractProduct::getContractId, Collectors.mapping(OemContractProduct::getFgItemId, Collectors.toSet())));
        return list.stream().map(c -> term(c, byContract.getOrDefault(c.getId(), Set.of()))).toList();
    }

    /** 成品现行 BOM 的行，按成品分组（一次查完）。 */
    public static Map<Long, List<ContractCoverage.Line>> bomLines(BomRepository boms, BomLineRepository bomLines, Collection<Long> fgIds, Map<Long, Item> items) {
        if (fgIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> fgByBom = boms.findByFgItemIdIn(fgIds).stream().collect(Collectors.toMap(Bom::getId, Bom::getFgItemId));
        if (fgByBom.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<ContractCoverage.Line>> out = new HashMap<>();
        for (BomLine l : bomLines.findByBomIdInOrderByBomIdAscLineNoAsc(fgByBom.keySet())) {
            Item i = items.get(l.getItemId());
            if (i != null) {
                out.computeIfAbsent(fgByBom.get(l.getBomId()), k -> new ArrayList<>()).add(new ContractCoverage.Line(i.getName(), i.getItemType(), l.getSupplyMode()));
            }
        }
        return out;
    }

    public static Term term(OemContract c, Set<Long> products) {
        return new Term(c.getId(), c.getContractNo(), c.getFactoryId(), products, APPROVED.contains(c.getStatus()), c.getValidFrom(), c.getValidTo(),
                c.getStatus() == ContractStatus.TERMINATED ? c.getTerminatedAt() : null);
    }
}
