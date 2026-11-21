package com.cosmetic.scm.plm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.ArtworkStatus;
import com.cosmetic.scm.domain.FilingStatus;
import com.cosmetic.scm.domain.FormulaStatus;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.NpdStatus;
import com.cosmetic.scm.domain.rules.FormulaCompliance;
import com.cosmetic.scm.master.BomLine;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.MasterService;
import com.cosmetic.scm.plm.ArtworkService.ArtworkView;
import com.cosmetic.scm.plm.ComplianceService.DocView;
import com.cosmetic.scm.plm.ComplianceService.IngredientList;
import com.cosmetic.scm.plm.FilingService.FilingView;
import com.cosmetic.scm.plm.NpdService.Check;
import com.cosmetic.scm.quality.QualitySpecService;
import com.cosmetic.scm.security.PriceAccess;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PLM 深度分析与合规档案服务（V46）：
 * 1) 配方任意双版本对比与单件物料成本差异测算（成本按价格权限隔离）；
 * 2) 单品产品信息档案（PIF）一键聚合；
 * 3) 原料/包材反查与替换影响评估（Where-Used）；
 * 4) 全线成品合规就绪度矩阵与新品立项（NPD）阶段门禁总览。
 */
@Service
@RequiredArgsConstructor
public class PlmInsightsService {

    private final FormulaVersionRepository versions;
    private final FormulaLineRepository formulaLines;
    private final ProductFilingRepository filingRepo;
    private final FilingService filings;
    private final ComplianceService compliance;
    private final StabilityService stability;
    private final LabelArtworkRepository artworkRepo;
    private final ArtworkService artworkService;
    private final QualitySpecService qualitySpecs;
    private final NpdProjectRepository npdRepo;
    private final NpdService npdService;
    private final MasterService master;
    private final MasterLookup lookup;
    private final TimeProvider time;

    // ---------- 1. 配方版本对比 ----------

    public record DiffLine(Long itemId, String itemCode, String itemName, String itemType, String unit,
                           String changeKind, String changeKindLabel,
                           BigDecimal fromQty, BigDecimal toQty, BigDecimal deltaQty,
                           BigDecimal fromPct, BigDecimal toPct,
                           String fromSupply, String toSupply, boolean affectsFormula) {
    }

    public record VersionDiffView(Long fgItemId, String fgCode, String fgName,
                                  Long fromVersionId, int fromVersionNo, String fromStatusLabel,
                                  Long toVersionId, int toVersionNo, String toStatusLabel,
                                  boolean requiresRefiling,
                                  BigDecimal fromMaterialCost, BigDecimal toMaterialCost,
                                  BigDecimal costDelta, BigDecimal costDeltaPct,
                                  List<DiffLine> lines) {
    }

    @Transactional(readOnly = true)
    public VersionDiffView compareVersions(Long fromVersionId, Long toVersionId) {
        FormulaVersion fromV = versions.findById(fromVersionId).orElseThrow(() -> BizException.notFound("基准配方版本"));
        FormulaVersion toV = versions.findById(toVersionId).orElseThrow(() -> BizException.notFound("对比配方版本"));
        if (!fromV.getFgItemId().equals(toV.getFgItemId())) {
            throw new BizException("只能对比同一成品的两个配方版本");
        }
        Item fg = lookup.item(fromV.getFgItemId());
        Map<Long, Item> items = lookup.itemMap();
        Map<Long, FormulaLine> fromMap = formulaLines.findByVersionIdOrderByLineNo(fromV.getId()).stream()
                .collect(Collectors.toMap(FormulaLine::getItemId, l -> l, (a, b) -> a, LinkedHashMap::new));
        Map<Long, FormulaLine> toMap = formulaLines.findByVersionIdOrderByLineNo(toV.getId()).stream()
                .collect(Collectors.toMap(FormulaLine::getItemId, l -> l, (a, b) -> a, LinkedHashMap::new));

        Set<Long> allItemIds = new LinkedHashSet<>();
        allItemIds.addAll(fromMap.keySet());
        allItemIds.addAll(toMap.keySet());

        List<DiffLine> diffLines = new ArrayList<>();
        boolean refiling = false;
        BigDecimal fromCost = BigDecimal.ZERO;
        BigDecimal toCost = BigDecimal.ZERO;
        boolean costComplete = true;

        for (Long id : allItemIds) {
            Item m = items.get(id);
            FormulaLine fl = fromMap.get(id);
            FormulaLine tl = toMap.get(id);
            BigDecimal fq = fl == null ? null : fl.getQtyPer();
            BigDecimal tq = tl == null ? null : tl.getQtyPer();
            String kind;
            String label;
            if (fl == null) {
                kind = "ADDED";
                label = "新增";
            } else if (tl == null) {
                kind = "REMOVED";
                label = "删除";
            } else if (fq.compareTo(tq) != 0 || fl.getSupplyMode() != tl.getSupplyMode()) {
                kind = "CHANGED";
                label = "调整";
            } else {
                kind = "UNCHANGED";
                label = "不变";
            }
            boolean rmChanged = m.getItemType() == ItemType.RM && !"UNCHANGED".equals(kind)
                    && (fl == null || tl == null || fq.compareTo(tq) != 0);
            if (rmChanged) {
                refiling = true;
            }
            BigDecimal delta = (tq == null ? BigDecimal.ZERO : tq).subtract(fq == null ? BigDecimal.ZERO : fq);
            diffLines.add(new DiffLine(id, m.getCode(), m.getName(), m.getItemType().name(), m.getUnit(),
                    kind, label, fq, tq, delta,
                    pctOf(fg, m, fq), pctOf(fg, m, tq),
                    fl == null ? null : fl.getSupplyMode().getLabel(),
                    tl == null ? null : tl.getSupplyMode().getLabel(),
                    rmChanged));

            if (m.getStdCost() == null) {
                costComplete = false;
            } else {
                if (fq != null) fromCost = fromCost.add(fq.multiply(m.getStdCost()));
                if (tq != null) toCost = toCost.add(tq.multiply(m.getStdCost()));
            }
        }

        boolean showPrice = PriceAccess.allowed();
        BigDecimal fc = showPrice && costComplete ? fromCost.setScale(4, RoundingMode.HALF_UP) : null;
        BigDecimal tc = showPrice && costComplete ? toCost.setScale(4, RoundingMode.HALF_UP) : null;
        BigDecimal cDelta = fc != null && tc != null ? tc.subtract(fc) : null;
        BigDecimal cPct = (cDelta != null && fc.signum() > 0)
                ? cDelta.multiply(BigDecimal.valueOf(100)).divide(fc, 1, RoundingMode.HALF_UP) : null;

        return new VersionDiffView(fg.getId(), fg.getCode(), fg.getName(),
                fromV.getId(), fromV.getVersionNo(), fromV.getStatus().getLabel(),
                toV.getId(), toV.getVersionNo(), toV.getStatus().getLabel(),
                refiling, fc, tc, cDelta, cPct, diffLines);
    }

    // ---------- 2. 产品信息档案 (PIF) ----------

    public record PifFormulaLine(String itemCode, String itemName, String itemType, String inci, String casNo,
                                 BigDecimal qtyPer, String unit, BigDecimal concentrationPct, String supplyModeLabel) {
    }

    public record PifVersionSummary(int versionNo, String statusLabel, String changeNote,
                                    String createdBy, LocalDateTime createdAt, String approvedBy, LocalDateTime approvedAt) {
    }

    public record PifView(Long fgItemId, String fgCode, String fgName, String unit, Integer shelfMonths,
                          BigDecimal netG, String filingNo, Integer effectiveVersionNo, String effectiveApprovedBy,
                          LocalDateTime effectiveApprovedAt, String qualitySpecNo, Integer stabilitySupportedMonths,
                          String stabilityState, List<PifFormulaLine> formulaLines, IngredientList ingredients,
                          List<FilingView> filings, List<DocView> complianceDocs, List<ArtworkView> artworks,
                          List<PifVersionSummary> versionHistory) {
    }

    @Transactional(readOnly = true)
    public PifView pif(Long fgItemId) {
        Item fg = lookup.item(fgItemId);
        if (fg.getItemType() != ItemType.FG) {
            throw new BizException(fg.getName() + " 不是成品");
        }
        Map<Long, Item> items = lookup.itemMap();
        List<FormulaVersion> fgVersions = versions.findByFgItemIdOrderByVersionNoDesc(fgItemId);
        FormulaVersion eff = fgVersions.stream().filter(v -> v.getStatus() == FormulaStatus.EFFECTIVE).findFirst().orElse(null);

        List<PifFormulaLine> fLines = new ArrayList<>();
        IngredientList ing = null;
        if (eff != null) {
            for (BomLine l : master.versionLines(eff.getId())) {
                Item m = items.get(l.getItemId());
                fLines.add(new PifFormulaLine(m.getCode(), m.getName(), m.getItemType().name(), m.getInci(), m.getCasNo(),
                        l.getQtyPer(), m.getUnit(), pctOf(fg, m, l.getQtyPer()), l.getSupplyMode().getLabel()));
            }
            ing = compliance.ingredients(fgItemId);
        }

        List<FilingView> fgFilings = filings.list().stream().filter(f -> fgItemId.equals(f.fgItemId())).toList();
        List<DocView> fgDocs = compliance.list().stream().filter(d -> fgItemId.equals(d.fgItemId())).toList();

        Set<Long> pkIds = eff == null ? Set.of()
                : master.versionLines(eff.getId()).stream().map(BomLine::getItemId)
                .filter(id -> items.get(id).getItemType() == ItemType.PK).collect(Collectors.toSet());
        List<ArtworkView> fgArtworks = pkIds.isEmpty() ? List.of()
                : artworkService.viewAll(artworkRepo.findByStatusInOrderById(List.of(ArtworkStatus.APPROVED)).stream()
                .filter(a -> pkIds.contains(a.getPkItemId())).toList());

        var stab = stability.coverage().stream().filter(c -> fgItemId.equals(c.fgItemId())).findFirst().orElse(null);
        var spec = qualitySpecs.effective(fgItemId).orElse(null);

        List<PifVersionSummary> history = fgVersions.stream()
                .map(v -> new PifVersionSummary(v.getVersionNo(), v.getStatus().getLabel(), v.getChangeNote(),
                        v.getCreatedBy(), v.getCreatedAt(), v.getApprovedBy(), v.getApprovedAt()))
                .toList();

        return new PifView(fg.getId(), fg.getCode(), fg.getName(), fg.getUnit(), fg.getShelfMonths(), fg.getNetG(),
                fg.getFilingNo(), eff == null ? null : eff.getVersionNo(),
                eff == null ? null : eff.getApprovedBy(), eff == null ? null : eff.getApprovedAt(),
                spec == null ? null : spec.specNo() + " V" + spec.versionNo(),
                stab == null ? null : stab.supportedMonths(), stab == null ? "NONE" : stab.state(),
                fLines, ing, fgFilings, fgDocs, fgArtworks, history);
    }

    // ---------- 3. 原料/包材反查 (Where-Used) ----------

    public record WhereUsedRow(Long itemId, String itemCode, String itemName,
                               Long fgItemId, String fgCode, String fgName, String filingNo,
                               Long versionId, int versionNo, String status, String statusLabel,
                               BigDecimal qtyPer, String unit, BigDecimal concentrationPct,
                               String supplyModeLabel, String checkLevel, String checkMessage) {
    }

    @Transactional(readOnly = true)
    public List<WhereUsedRow> whereUsed(Long itemId) {
        Item target = lookup.item(itemId);
        if (target.getItemType() == ItemType.FG) {
            throw new BizException("请选择原料或包材进行反查");
        }
        return scanWhereUsed(itemId);
    }

    @Transactional(readOnly = true)
    public List<WhereUsedRow> whereUsedAllEffective() {
        return scanWhereUsed(null);
    }

    private List<WhereUsedRow> scanWhereUsed(Long filterItemId) {
        List<FormulaVersion> activeVers = versions.findAll().stream()
                .filter(v -> v.getStatus() == FormulaStatus.EFFECTIVE
                        || (filterItemId != null && (v.getStatus() == FormulaStatus.PENDING || v.getStatus() == FormulaStatus.DRAFT)))
                .toList();
        if (activeVers.isEmpty()) {
            return List.of();
        }
        Map<Long, FormulaVersion> vMap = activeVers.stream().collect(Collectors.toMap(FormulaVersion::getId, v -> v));
        Map<Long, Item> items = lookup.itemMap();
        List<FormulaLine> lines = formulaLines.findByVersionIdIn(vMap.keySet());

        List<WhereUsedRow> out = new ArrayList<>();
        for (FormulaLine l : lines) {
            if (filterItemId != null && !filterItemId.equals(l.getItemId())) {
                continue;
            }
            FormulaVersion v = vMap.get(l.getVersionId());
            Item fg = items.get(v.getFgItemId());
            Item m = items.get(l.getItemId());
            var check = master.lineView(fg, m, l.getQtyPer(), l.getSupplyMode());
            out.add(new WhereUsedRow(m.getId(), m.getCode(), m.getName(),
                    fg.getId(), fg.getCode(), fg.getName(), fg.getFilingNo(),
                    v.getId(), v.getVersionNo(), v.getStatus().name(), v.getStatus().getLabel(),
                    l.getQtyPer(), m.getUnit(), check.concentrationPct(),
                    l.getSupplyMode().getLabel(), check.checkLevel(), check.checkMessage()));
        }
        out.sort(Comparator.comparing(WhereUsedRow::itemCode)
                .thenComparing(WhereUsedRow::fgCode)
                .thenComparing(Comparator.comparingInt(WhereUsedRow::versionNo).reversed()));
        return out;
    }

    // ---------- 4. 全线成品合规就绪度 & NPD 上市就绪度矩阵 ----------

    public record ProductReadinessRow(Long fgItemId, String fgCode, String fgName, Integer effectiveVersionNo,
                                      String filingNo, String safetyState, String labelState,
                                      String stabilityState, Integer supportedMonths, boolean hasQualitySpec,
                                      boolean readyForRelease) {
    }

    @Transactional(readOnly = true)
    public List<ProductReadinessRow> productReadiness() {
        Map<Long, ComplianceService.ProductStatus> compMap = compliance.statuses().stream()
                .collect(Collectors.toMap(ComplianceService.ProductStatus::fgItemId, s -> s));
        Map<Long, StabilityService.ProductCoverage> stabMap = stability.coverage().stream()
                .collect(Collectors.toMap(StabilityService.ProductCoverage::fgItemId, s -> s));
        Map<Long, FormulaVersion> effMap = versions.findByStatus(FormulaStatus.EFFECTIVE).stream()
                .collect(Collectors.toMap(FormulaVersion::getFgItemId, v -> v, (a, b) -> a));

        List<ProductReadinessRow> rows = new ArrayList<>();
        for (Item fg : lookup.itemMap().values().stream()
                .filter(i -> i.getItemType() == ItemType.FG && i.isActive())
                .sorted(Comparator.comparing(Item::getCode)).toList()) {
            var eff = effMap.get(fg.getId());
            var cs = compMap.get(fg.getId());
            var st = stabMap.get(fg.getId());
            boolean hasSpec = qualitySpecs.effective(fg.getId()).isPresent();
            boolean hasFiling = !filingRepo.findByFgItemIdAndStatusOrderByIdDesc(fg.getId(), FilingStatus.FILED).isEmpty()
                    || (fg.getFilingNo() != null && !fg.getFilingNo().isBlank());
            boolean ready = eff != null && hasFiling && cs != null && cs.complete()
                    && st != null && "OK".equals(st.state()) && hasSpec;
            rows.add(new ProductReadinessRow(fg.getId(), fg.getCode(), fg.getName(),
                    eff == null ? null : eff.getVersionNo(), fg.getFilingNo(),
                    cs == null ? "MISSING" : cs.safety(), cs == null ? "MISSING" : cs.label(),
                    st == null ? "NONE" : st.state(), st == null ? null : st.supportedMonths(),
                    hasSpec, ready));
        }
        return rows;
    }

    public record NpdReadinessRow(Long projectId, String projectNo, String name, String category,
                                  String filingTypeLabel, String status, String statusLabel,
                                  LocalDate targetLaunch, long daysLeft, boolean late,
                                  String fgCode, String fgName, int passedCount, int totalCount,
                                  BigDecimal readinessPct, boolean ready, List<String> missingItems,
                                  List<Check> checks) {
    }

    @Transactional(readOnly = true)
    public List<NpdReadinessRow> npdReadiness() {
        LocalDate today = time.today();
        Map<Long, Item> items = lookup.itemMap();
        List<NpdReadinessRow> out = new ArrayList<>();
        for (NpdProject p : npdRepo.findTop300ByOrderByIdDesc()) {
            if (p.getStatus() == NpdStatus.CANCELLED || p.getStatus() == NpdStatus.REJECTED) {
                continue;
            }
            List<Check> checks = p.getStatus() == NpdStatus.PROPOSED ? List.of() : npdService.checks(p);
            int total = checks.size();
            int passed = (int) checks.stream().filter(Check::ok).count();
            BigDecimal pct = total == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(passed).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
            List<String> missing = checks.stream().filter(c -> !c.ok()).map(Check::title).toList();
            Item fg = p.getFgItemId() == null ? null : items.get(p.getFgItemId());
            long daysLeft = p.getTargetLaunch() == null ? 0 : ChronoUnit.DAYS.between(today, p.getTargetLaunch());
            boolean late = (p.getStatus() == NpdStatus.ACTIVE || p.getStatus() == NpdStatus.LAUNCH_REVIEW) && daysLeft < 0;
            out.add(new NpdReadinessRow(p.getId(), p.getProjectNo(), p.getName(), p.getCategory(),
                    p.getFilingType().getLabel(), p.getStatus().name(), p.getStatus().getLabel(),
                    p.getTargetLaunch(), daysLeft, late, fg == null ? null : fg.getCode(),
                    fg == null ? null : fg.getName(), passed, total, pct,
                    total > 0 && passed == total, missing, checks));
        }
        return out;
    }

    private static BigDecimal pctOf(Item fg, Item m, BigDecimal qtyPer) {
        if (qtyPer == null || m.getItemType() != ItemType.RM || fg.getNetG() == null || fg.getNetG().signum() <= 0) {
            return null;
        }
        BigDecimal g = FormulaCompliance.toGrams(qtyPer, m.getUnit());
        return g == null ? null : g.multiply(BigDecimal.valueOf(100)).divide(fg.getNetG(), 4, RoundingMode.HALF_UP);
    }
}
