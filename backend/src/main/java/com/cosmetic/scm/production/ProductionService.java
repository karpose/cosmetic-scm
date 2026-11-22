package com.cosmetic.scm.production;

import com.cosmetic.scm.common.Qty;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.DocNoService;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.MoStatus;
import com.cosmetic.scm.domain.MoType;
import com.cosmetic.scm.domain.OemReportStatus;
import com.cosmetic.scm.domain.OemStage;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.SourceType;
import com.cosmetic.scm.domain.SupplyMode;
import com.cosmetic.scm.domain.TxnType;
import com.cosmetic.scm.domain.WarehouseKind;
import com.cosmetic.scm.domain.rules.FefoAllocator;
import com.cosmetic.scm.domain.rules.ShelfLife;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchGenealogy;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.inventory.GenealogyRepository;
import com.cosmetic.scm.inventory.InventoryService;
import com.cosmetic.scm.master.BomLine;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.MasterService;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.Warehouse;
import com.cosmetic.scm.master.WarehouseRepository;
import com.cosmetic.scm.mes.MesService;
import com.cosmetic.scm.oemportal.OemProgress;
import com.cosmetic.scm.oemportal.OemProgressRepository;
import com.cosmetic.scm.oemportal.OemReport;
import com.cosmetic.scm.oemportal.OemReportRepository;
import com.cosmetic.scm.plm.FormulaVersion;
import com.cosmetic.scm.plm.FormulaVersionRepository;
import com.cosmetic.scm.qms.DeviationRepository;
import com.cosmetic.scm.security.CurrentUser;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 生产与委托：自产工单和委托生产单走同一流程。
 * 品牌供料部分按 FEFO 从自有仓发料；若受托厂配置了寄存仓，发料同步入寄存仓并在完工时自动核销；
 * 生产过程中支持超耗补料与余料退库；工厂代采部分在完工时登记受托厂的原料批号。完工批次一律进入“待上市放行”。
 */
@Service
@RequiredArgsConstructor
public class ProductionService {

    /** 实际产量 / 计划产量 超出此区间时，必须填写物料平衡说明（GMP 第四十一条）。 */
    public static final BigDecimal YIELD_MIN = new BigDecimal("0.95");
    public static final BigDecimal YIELD_MAX = new BigDecimal("1.02");

    private final MfgOrderRepository orders;
    private final MfgIssueRepository issues;
    private final MasterService master;
    private final MasterLookup lookup;
    private final WarehouseRepository warehouses;
    private final InventoryService inventory;
    private final BatchRepository batches;
    private final GenealogyRepository genealogy;
    private final OemProgressRepository oemProgress;
    private final OemReportRepository oemReports;
    private final FormulaVersionRepository formulaVersions;
    private final DeviationRepository deviations;
    private final MesService mes;
    private final DocNoService docNo;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;
    private final com.cosmetic.scm.settlement.SupplierStatementLineRepository statementLines;

    public record CreateMoReq(@NotNull Long factoryId, @NotNull Long fgItemId,
                              @NotNull @Positive @Qty BigDecimal plannedQty, LocalDate plannedDate) {
    }

    public record ExtMaterialReq(@NotNull Long itemId,
                                 @NotBlank(message = "请填写受托厂原料批号") @Size(max = 60) String extBatchNo,
                                 @NotNull @Positive @Qty BigDecimal qty) {
    }

    public record CompleteReq(@NotNull @Positive @Qty BigDecimal actualQty,
                              LocalDate mfgDate,
                              @Size(max = 60) String extBatchNo,
                              @NotNull Long warehouseId,
                              boolean oemReleased,
                              @Size(max = 300) String balanceNote,
                              @Valid List<ExtMaterialReq> extMaterials) {
    }

    public record CancelReq(@NotBlank(message = "请填写取消原因") @Size(max = 300) String reason) {
    }

    public record SupplementReq(@NotNull Long itemId,
                                @NotNull @Positive @Qty BigDecimal qty,
                                @NotBlank(message = "请填写补料原因") @Size(max = 300) String reason) {
    }

    public record ReturnMaterialReq(@NotNull Long batchId,
                                    @NotNull Long warehouseId,
                                    @NotNull @Positive @Qty BigDecimal qty,
                                    @NotBlank(message = "请填写退库原因") @Size(max = 300) String reason) {
    }

    public record SetFeeReq(@NotNull(message = "请填写单位加工费") @Positive(message = "加工费要大于 0")
                            @jakarta.validation.constraints.Digits(integer = 14, fraction = 4, message = "加工费最多 14 位整数、4 位小数") BigDecimal processingUnitFee,
                            @jakarta.validation.constraints.DecimalMin(value = "0", message = "税率不能为负")
                            @jakarta.validation.constraints.DecimalMax(value = "100", message = "税率不能超过 100")
                            @jakarta.validation.constraints.Digits(integer = 3, fraction = 2, message = "税率最多 2 位小数") BigDecimal processingTaxRate,
                            @Size(max = 300) String reason) {
    }

    public record PickView(Long batchId, String batchNo, Long warehouseId, String warehouseName, BigDecimal qty,
                           LocalDate expDate) {
    }

    public record IssueLogView(Long id, String issueType, String issueTypeLabel, Long itemId, String itemCode,
                               String itemName, String unit, Long batchId, String batchNo, Long warehouseId,
                               String warehouseName, BigDecimal qty, String reason, String createdBy,
                               LocalDateTime createdAt) {
    }

    public record OemConsignmentRow(Long factoryId, String factoryName, Long warehouseId, String warehouseCode,
                                    String warehouseName, Long batchId, String batchNo, Long itemId, String itemCode,
                                    String itemName, String itemType, String unit, BigDecimal qty,
                                    BatchStatus status, String statusLabel, LocalDate expDate,
                                    BigDecimal unitCost, BigDecimal value) {
    }

    public record MaterialVarianceRow(Long moId, String moNo, MoType moType, String moTypeLabel,
                                      Long factoryId, String factoryName, Long fgItemId, String fgCode, String fgName,
                                      MoStatus moStatus, String moStatusLabel, BigDecimal plannedQty, BigDecimal actualQty,
                                      Long itemId, String itemCode, String itemName, String unit,
                                      BigDecimal standardQty, BigDecimal netIssuedQty, BigDecimal varianceQty,
                                      BigDecimal variancePct, String state, String stateLabel,
                                      BigDecimal unitCost, BigDecimal varianceCost) {
    }

    public record RequirementView(Long itemId, String itemCode, String itemName, String unit, SupplyMode supplyMode,
                                  String supplyLabel, BigDecimal requiredQty, BigDecimal issuedQty,
                                  List<PickView> picks, BigDecimal shortQty, String extBatchNo, BigDecimal extQty) {
    }

    public record MoView(Long id, String moNo, MoType moType, String moTypeLabel, Long factoryId, String factoryName,
                         Long fgItemId, String fgCode, String fgName, BigDecimal plannedQty, LocalDate plannedDate,
                         MoStatus status, String statusLabel, String outBatchNo, BigDecimal actualQty,
                         BigDecimal yieldPct, BigDecimal processingUnitFee, BigDecimal processingTaxRate,
                         String createdBy, LocalDateTime createdAt,
                         String oemStageLabel, OemReportStatus oemReportStatus, String oemReportStatusLabel,
                         String cancelReason, String cancelledBy, LocalDateTime cancelledAt) {
    }

    public record MoDetail(MoView mo, List<RequirementView> requirements, Integer formulaVersionNo,
                           String oemWarehouseName, List<IssueLogView> issueLogs) {
    }

    private record Requirement(BomLine line, Item item, SupplyMode mode, BigDecimal qty) {
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public List<MoView> list() {
        return views(orders.findTop300ByOrderByIdDesc());
    }

    /** 全部生产单，导出用（页面列表只取最近 300 张）。 */
    @Transactional(readOnly = true)
    public List<MoView> listAll() {
        return views(orders.findAllByOrderByIdDesc());
    }

    private List<MoView> views(List<MfgOrder> list) {
        Map<Long, Item> im = lookup.itemMap();
        Map<Long, Partner> pm = lookup.partnerMap();
        Map<Long, String> outNos = batches.findAllById(list.stream().map(MfgOrder::getOutBatchId)
                        .filter(java.util.Objects::nonNull).toList()).stream()
                .collect(Collectors.toMap(Batch::getId, Batch::getBatchNo));
        // 受托厂门户回传的最新进度和申报状态，列表上直接显示
        List<Long> ids = list.stream().map(MfgOrder::getId).toList();
        Map<Long, String> stages = new java.util.HashMap<>();
        Map<Long, OemReportStatus> reportStates = new java.util.HashMap<>();
        if (!ids.isEmpty()) {
            for (OemProgress p : oemProgress.findByMoIdInOrderByIdAsc(ids)) {
                if (p.getStage() != OemStage.NOTE) {
                    stages.put(p.getMoId(), p.getStage().getLabel());
                }
            }
            for (OemReport r : oemReports.findByMoIdInOrderByIdAsc(ids)) {
                reportStates.put(r.getMoId(), r.getStatus());
            }
        }
        return list.stream().map(m -> toView(m, im, pm, outNos, stages.get(m.getId()), reportStates.get(m.getId()))).toList();
    }

    /** 生产单详情。已计划的单据给出 FEFO 发料预览；已发料的给出实际净发料批次及发料/补料/退库记录。 */
    @Transactional(readOnly = true)
    public MoDetail detail(Long moId) {
        MfgOrder mo = require(moId);
        Map<Long, Item> im = lookup.itemMap();
        Map<Long, Partner> pm = lookup.partnerMap();
        Map<Long, Warehouse> wm = lookup.warehouseMap();
        List<MfgIssue> done = issues.findByMoIdOrderByIdAsc(moId);
        Map<Long, Batch> issuedBatches = batches.findAllById(done.stream().map(MfgIssue::getBatchId).distinct().toList())
                .stream().collect(Collectors.toMap(Batch::getId, Function.identity()));
        List<BatchGenealogy> gen = mo.getOutBatchId() == null ? List.of() : genealogy.findByChildBatchId(mo.getOutBatchId());
        List<Long> own = ownWarehouseIds();

        List<RequirementView> reqs = new ArrayList<>();
        for (Requirement r : requirements(mo)) {
            List<PickView> picks = new ArrayList<>();
            BigDecimal issued = BigDecimal.ZERO;
            BigDecimal shortQty = BigDecimal.ZERO;
            String extNo = null;
            BigDecimal extQty = null;
            if (r.mode() == SupplyMode.BRAND) {
                if (mo.getStatus() == MoStatus.PLANNED) {
                    FefoAllocator.Result a = inventory.allocate(r.item().getId(), r.qty(), own, 0);
                    for (FefoAllocator.Pick p : a.picks()) {
                        picks.add(new PickView(p.batchId(), p.batchNo(), p.warehouseId(), wm.get(p.warehouseId()).getName(),
                                p.qty(), p.expDate()));
                    }
                    shortQty = a.shortQty();
                } else {
                    Map<String, BigDecimal> netByKey = new LinkedHashMap<>();
                    Map<String, MfgIssue> sampleByKey = new LinkedHashMap<>();
                    for (MfgIssue i : done) {
                        if (i.getItemId().equals(r.item().getId())) {
                            String key = i.getBatchId() + "-" + i.getWarehouseId();
                            netByKey.merge(key, i.getQty(), BigDecimal::add);
                            sampleByKey.putIfAbsent(key, i);
                            issued = issued.add(i.getQty());
                        }
                    }
                    for (Map.Entry<String, BigDecimal> e : netByKey.entrySet()) {
                        if (e.getValue().signum() <= 0) {
                            continue;
                        }
                        MfgIssue s = sampleByKey.get(e.getKey());
                        Batch b = issuedBatches.get(s.getBatchId());
                        picks.add(new PickView(b.getId(), b.getBatchNo(), s.getWarehouseId(),
                                wm.get(s.getWarehouseId()).getName(), e.getValue(), b.getExpDate()));
                    }
                }
            } else {
                for (BatchGenealogy g : gen) {
                    if (g.getSupplyMode() == SupplyMode.OEM && g.getItemId().equals(r.item().getId())) {
                        extNo = g.getExtBatchNo();
                        extQty = g.getQty();
                    }
                }
            }
            reqs.add(new RequirementView(r.item().getId(), r.item().getCode(), r.item().getName(), r.item().getUnit(),
                    r.mode(), r.mode().getLabel(), r.qty(), issued, picks, shortQty, extNo, extQty));
        }
        List<IssueLogView> logs = new ArrayList<>();
        for (MfgIssue i : done) {
            Item it = im.get(i.getItemId());
            Batch b = issuedBatches.get(i.getBatchId());
            Warehouse w = wm.get(i.getWarehouseId());
            String type = i.getIssueType() == null ? "INITIAL" : i.getIssueType();
            logs.add(new IssueLogView(i.getId(), type, issueTypeLabel(type), i.getItemId(),
                    it == null ? null : it.getCode(), it == null ? null : it.getName(), it == null ? null : it.getUnit(),
                    i.getBatchId(), b == null ? null : b.getBatchNo(), i.getWarehouseId(),
                    w == null ? null : w.getName(), i.getQty(), i.getReason(), i.getCreatedBy(), i.getCreatedAt()));
        }
        String oemWhName = mo.getOemWarehouseId() == null ? null
                : (wm.get(mo.getOemWarehouseId()) == null ? null : wm.get(mo.getOemWarehouseId()).getName());
        Map<Long, String> outNos = mo.getOutBatchId() == null ? Map.of()
                : Map.of(mo.getOutBatchId(), inventory.batch(mo.getOutBatchId()).getBatchNo());
        Integer versionNo = mo.getFormulaVersionId() == null ? null
                : formulaVersions.findById(mo.getFormulaVersionId()).map(FormulaVersion::getVersionNo).orElse(null);
        return new MoDetail(toView(mo, im, pm, outNos), reqs, versionNo, oemWhName, logs);
    }

    /** 委外寄售料账：汇总各受托厂寄存仓中当前在厂的原料与包材余料（价格字段按 PriceAccess 权限控制）。 */
    @Transactional(readOnly = true)
    public List<OemConsignmentRow> oemConsignment() {
        Map<Long, Warehouse> oemWhs = warehouses.findByKind(WarehouseKind.OEM).stream()
                .collect(Collectors.toMap(Warehouse::getId, Function.identity()));
        if (oemWhs.isEmpty()) {
            return List.of();
        }
        Map<Long, Partner> pm = lookup.partnerMap();
        boolean showPrice = com.cosmetic.scm.security.PriceAccess.allowed();
        Map<Long, BigDecimal> costs = showPrice ? master.unitCosts() : Map.of();
        List<OemConsignmentRow> rows = new ArrayList<>();
        for (InventoryService.StockRow s : inventory.listStock(null, null, null, false)) {
            Warehouse w = oemWhs.get(s.warehouseId());
            if (w == null || "FG".equals(s.itemType())) {
                continue;
            }
            Partner f = w.getPartnerId() == null ? null : pm.get(w.getPartnerId());
            BigDecimal unitCost = showPrice ? costs.get(s.itemId()) : null;
            BigDecimal val = unitCost == null ? null : unitCost.multiply(s.qty()).setScale(2, RoundingMode.HALF_UP);
            rows.add(new OemConsignmentRow(w.getPartnerId(), f == null ? "未关联受托厂" : f.getName(),
                    w.getId(), w.getCode(), w.getName(), s.batchId(), s.batchNo(), s.itemId(), s.itemCode(),
                    s.itemName(), s.itemType(), s.unit(), s.qty(), s.status(), s.statusLabel(), s.expDate(),
                    unitCost, val));
        }
        return rows;
    }

    /** 生产与委外物料超耗与平衡台账：对比每张已发料/已完工工单的品牌供料理论标耗与净发料量（差异金额按 PriceAccess 权限控制）。 */
    @Transactional(readOnly = true)
    public List<MaterialVarianceRow> materialVariance() {
        List<MfgOrder> mos = orders.findAllByOrderByIdDesc().stream()
                .filter(m -> m.getStatus() == MoStatus.ISSUED || m.getStatus() == MoStatus.COMPLETED)
                .toList();
        return computeMaterialVariance(mos, com.cosmetic.scm.security.PriceAccess.allowed(), false);
    }

    /** 供委外对账单预览调用：返回指定已完工委外工单中存在品牌供料超耗（netIssued > standard）的明细及超耗金额。 */
    @Transactional(readOnly = true)
    public List<MaterialVarianceRow> overuseForSettlement(java.util.Collection<Long> moIds) {
        if (moIds == null || moIds.isEmpty()) {
            return List.of();
        }
        return computeMaterialVariance(orders.findAllById(moIds), true, true);
    }

    private List<MaterialVarianceRow> computeMaterialVariance(List<MfgOrder> mos, boolean showPrice, boolean onlyOveruse) {
        if (mos.isEmpty()) {
            return List.of();
        }
        Map<Long, Item> im = lookup.itemMap();
        Map<Long, Partner> pm = lookup.partnerMap();
        Map<Long, BigDecimal> costs = showPrice ? master.unitCosts() : Map.of();
        List<Long> moIds = mos.stream().map(MfgOrder::getId).toList();
        Map<String, BigDecimal> netByMoItem = new java.util.HashMap<>();
        for (MfgIssue i : issues.findByMoIdIn(moIds)) {
            netByMoItem.merge(i.getMoId() + ":" + i.getItemId(), i.getQty(), BigDecimal::add);
        }
        List<MaterialVarianceRow> rows = new ArrayList<>();
        for (MfgOrder mo : mos) {
            Item fg = im.get(mo.getFgItemId());
            Partner f = pm.get(mo.getFactoryId());
            BigDecimal baseQty = (mo.getStatus() == MoStatus.COMPLETED && mo.getActualQty() != null && mo.getActualQty().signum() > 0)
                    ? mo.getActualQty() : mo.getPlannedQty();
            List<BomLine> lines = mo.getFormulaVersionId() == null ? master.bomLines(mo.getFgItemId())
                    : master.versionLines(mo.getFormulaVersionId());
            for (BomLine l : lines) {
                SupplyMode mode = mo.getMoType() == MoType.OWN ? SupplyMode.BRAND : l.getSupplyMode();
                if (mode != SupplyMode.BRAND) {
                    continue;
                }
                Item mat = im.get(l.getItemId());
                if (mat == null) {
                    continue;
                }
                BigDecimal stdQty = l.getQtyPer().multiply(baseQty).setScale(4, RoundingMode.UP);
                BigDecimal netIssued = netByMoItem.getOrDefault(mo.getId() + ":" + l.getItemId(), BigDecimal.ZERO);
                BigDecimal variance = netIssued.subtract(stdQty);
                if (onlyOveruse && variance.signum() <= 0) {
                    continue;
                }
                BigDecimal varPct = stdQty.signum() > 0
                        ? variance.multiply(BigDecimal.valueOf(100)).divide(stdQty, 1, RoundingMode.HALF_UP)
                        : null;
                String state = variance.signum() > 0 ? "OVERUSE" : variance.signum() < 0 ? "SAVING" : "BALANCED";
                String stateLabel = variance.signum() > 0 ? "超耗" : variance.signum() < 0 ? "节余" : "平衡";
                BigDecimal unitCost = showPrice ? costs.get(l.getItemId()) : null;
                BigDecimal varCost = unitCost == null ? null : variance.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
                rows.add(new MaterialVarianceRow(mo.getId(), mo.getMoNo(), mo.getMoType(), mo.getMoType().getLabel(),
                        mo.getFactoryId(), f == null ? "-" : f.getName(), mo.getFgItemId(),
                        fg == null ? "-" : fg.getCode(), fg == null ? "-" : fg.getName(),
                        mo.getStatus(), mo.getStatus().getLabel(), mo.getPlannedQty(), mo.getActualQty(),
                        mat.getId(), mat.getCode(), mat.getName(), mat.getUnit(),
                        stdQty, netIssued, variance, varPct, state, stateLabel, unitCost, varCost));
            }
        }
        return rows;
    }

    // ---------- 写操作 ----------

    @Transactional
    public MoView create(CreateMoReq req) {
        LocalDate today = time.today();
        Partner factory = lookup.partner(req.factoryId());
        if (factory.getKind() != PartnerKind.OEM && factory.getKind() != PartnerKind.OWN_FACTORY) {
            throw new BizException(factory.getName() + " 不是生产方，请选择受托生产企业或自有工厂");
        }
        if (!factory.isQualified()) {
            throw new BizException(factory.getName() + " 不在合格名录内，不能下单");
        }
        if (factory.licenseExpired(today)) {
            throw new BizException(factory.getName() + " 的生产许可证已于 " + factory.getLicenseExpiry() + " 过期，不能下单");
        }
        Item fg = lookup.item(req.fgItemId());
        if (fg.getItemType() != ItemType.FG) {
            throw new BizException(fg.getName() + " 不是成品");
        }
        if (master.bomLines(fg.getId()).isEmpty()) {
            throw new BizException(fg.getName() + " 还没有维护 BOM，不能下生产单");
        }
        MfgOrder mo = new MfgOrder();
        mo.setMoNo(docNo.next("MO", today));
        mo.setMoType(factory.getKind() == PartnerKind.OWN_FACTORY ? MoType.OWN : MoType.OEM);
        mo.setFactoryId(factory.getId());
        mo.setFgItemId(fg.getId());
        mo.setPlannedQty(req.plannedQty());
        mo.setPlannedDate(req.plannedDate() == null ? today : req.plannedDate());
        mo.setFormulaVersionId(master.effectiveVersionId(fg.getId()));
        mo.setStatus(MoStatus.PLANNED);
        mo.setCreatedBy(currentUser.usernameOrSystem());
        mo.setCreatedAt(time.now());
        orders.save(mo);
        audit.log(mo.getMoType() == MoType.OWN ? "新建自产工单" : "新建委托单", mo.getMoNo(),
                fg.getName() + " × " + plain(req.plannedQty()) + "，" + factory.getName());
        return toView(mo, lookup.itemMap(), lookup.partnerMap(), Map.of());
    }

    /** 品牌供料部分按 FEFO 从自有仓发料；若委外厂配置了寄存仓，则同步入寄存仓形成委外寄售料账。任何一种物料不足，整单不发。 */
    @Transactional
    public MoView issue(Long moId) {
        MfgOrder mo = lock(moId);
        if (mo.getStatus() != MoStatus.PLANNED) {
            throw new BizException(mo.getMoNo() + " 当前状态为" + mo.getStatus().getLabel() + "，不能发料");
        }
        List<Long> own = ownWarehouseIds();
        List<String> shortages = new ArrayList<>();
        Map<Requirement, FefoAllocator.Result> plan = new LinkedHashMap<>();
        for (Requirement r : requirements(mo)) {
            if (r.mode() != SupplyMode.BRAND) {
                continue;
            }
            FefoAllocator.Result a = inventory.allocateForUpdate(r.item().getId(), r.qty(), own, 0);
            if (!a.fulfilled()) {
                shortages.add(r.item().getName() + " 缺 " + plain(a.shortQty()) + " " + r.item().getUnit());
            }
            plan.put(r, a);
        }
        if (!shortages.isEmpty()) {
            throw new BizException("已放行库存不足，无法发料：" + String.join("；", shortages));
        }
        Warehouse oemWh = mo.getMoType() == MoType.OEM
                ? warehouses.findFirstByKindAndPartnerIdOrderByIdAsc(WarehouseKind.OEM, mo.getFactoryId()).orElse(null)
                : null;
        LocalDateTime now = time.now();
        String actor = currentUser.usernameOrSystem();
        List<String> detail = new ArrayList<>();
        for (Map.Entry<Requirement, FefoAllocator.Result> e : plan.entrySet()) {
            inventory.consume(e.getValue().picks(), TxnType.ISSUE, mo.getMoNo());
            for (FefoAllocator.Pick p : e.getValue().picks()) {
                if (oemWh != null) {
                    inventory.change(inventory.batch(p.batchId()), oemWh.getId(), p.qty(), TxnType.ISSUE, mo.getMoNo());
                }
                MfgIssue i = new MfgIssue();
                i.setMoId(mo.getId());
                i.setBatchId(p.batchId());
                i.setWarehouseId(p.warehouseId());
                i.setItemId(e.getKey().item().getId());
                i.setQty(p.qty());
                i.setIssueType("INITIAL");
                i.setCreatedBy(actor);
                i.setCreatedAt(now);
                issues.save(i);
                detail.add(p.batchNo() + "×" + plain(p.qty()));
            }
        }
        if (oemWh != null && !detail.isEmpty()) {
            mo.setOemWarehouseId(oemWh.getId());
        }
        mo.setStatus(MoStatus.ISSUED);
        mo.setIssuedAt(now);
        orders.save(mo);
        mes.createRecord(mo);
        String oemNote = (oemWh != null && !detail.isEmpty()) ? "（入 " + oemWh.getName() + "）" : "";
        audit.log("生产发料", mo.getMoNo(), (detail.isEmpty() ? "无品牌供料物料" : String.join("，", detail)) + oemNote);
        return toView(mo, lookup.itemMap(), lookup.partnerMap(), Map.of());
    }

    /** 生产补料（超耗领料）：已发料工单按 FEFO 追加领用品牌供料，自动计入寄售仓与完工谱系。 */
    @Transactional
    public MoDetail supplement(Long moId, SupplementReq req) {
        MfgOrder mo = lock(moId);
        if (mo.getStatus() != MoStatus.ISSUED) {
            throw new BizException(mo.getMoNo() + " 当前状态为" + mo.getStatus().getLabel() + "，只有已发料工单才能补料");
        }
        Requirement matched = requirements(mo).stream()
                .filter(r -> r.mode() == SupplyMode.BRAND && r.item().getId().equals(req.itemId()))
                .findFirst()
                .orElseThrow(() -> new BizException("该物料不在本生产单的品牌供料 BOM 清单中，不能补料"));
        FefoAllocator.Result alloc = inventory.allocateForUpdate(req.itemId(), req.qty(), ownWarehouseIds(), 0);
        if (!alloc.fulfilled()) {
            throw new BizException(matched.item().getName() + " 自有仓已放行库存不足，缺 " + plain(alloc.shortQty()) + " " + matched.item().getUnit());
        }
        inventory.consume(alloc.picks(), TxnType.ISSUE, mo.getMoNo());
        Long oemWhId = mo.getOemWarehouseId();
        if (oemWhId == null && mo.getMoType() == MoType.OEM) {
            oemWhId = warehouses.findFirstByKindAndPartnerIdOrderByIdAsc(WarehouseKind.OEM, mo.getFactoryId())
                    .map(Warehouse::getId).orElse(null);
            if (oemWhId != null) {
                mo.setOemWarehouseId(oemWhId);
                orders.save(mo);
            }
        }
        LocalDateTime now = time.now();
        String actor = currentUser.usernameOrSystem();
        String reason = req.reason().trim();
        List<String> detail = new ArrayList<>();
        for (FefoAllocator.Pick p : alloc.picks()) {
            if (oemWhId != null) {
                inventory.change(inventory.batch(p.batchId()), oemWhId, p.qty(), TxnType.ISSUE, mo.getMoNo());
            }
            MfgIssue i = new MfgIssue();
            i.setMoId(mo.getId());
            i.setBatchId(p.batchId());
            i.setWarehouseId(p.warehouseId());
            i.setItemId(req.itemId());
            i.setQty(p.qty());
            i.setIssueType("SUPPLEMENT");
            i.setReason(reason);
            i.setCreatedBy(actor);
            i.setCreatedAt(now);
            issues.save(i);
            detail.add(p.batchNo() + "×" + plain(p.qty()));
        }
        audit.log("生产补料", mo.getMoNo(), matched.item().getName() + "：" + String.join("，", detail), reason);
        return detail(moId);
    }

    /** 生产余料退库：将已发料工单未用完的指定批次退回原自有仓（委外单同步核减寄存仓）。 */
    @Transactional
    public MoDetail returnMaterial(Long moId, ReturnMaterialReq req) {
        MfgOrder mo = lock(moId);
        if (mo.getStatus() != MoStatus.ISSUED) {
            throw new BizException(mo.getMoNo() + " 当前状态为" + mo.getStatus().getLabel() + "，只有已发料工单才能余料退库");
        }
        List<MfgIssue> list = issues.findByMoIdOrderByIdAsc(moId);
        BigDecimal netIssued = BigDecimal.ZERO;
        Long itemId = null;
        for (MfgIssue i : list) {
            if (i.getBatchId().equals(req.batchId()) && i.getWarehouseId().equals(req.warehouseId())) {
                netIssued = netIssued.add(i.getQty());
                itemId = i.getItemId();
            }
        }
        if (itemId == null || netIssued.signum() <= 0) {
            throw new BizException("该批次在该仓库没有可退余料");
        }
        if (req.qty().compareTo(netIssued) > 0) {
            throw new BizException("退库数量不能超过该批次的净发料量 " + plain(netIssued));
        }
        Batch b = inventory.batch(req.batchId());
        if (mo.getOemWarehouseId() != null) {
            inventory.change(b, mo.getOemWarehouseId(), req.qty().negate(), TxnType.ISSUE_BACK, mo.getMoNo());
        }
        inventory.change(b, req.warehouseId(), req.qty(), TxnType.ISSUE_BACK, mo.getMoNo());
        String reason = req.reason().trim();
        MfgIssue ret = new MfgIssue();
        ret.setMoId(mo.getId());
        ret.setBatchId(req.batchId());
        ret.setWarehouseId(req.warehouseId());
        ret.setItemId(itemId);
        ret.setQty(req.qty().negate());
        ret.setIssueType("RETURN");
        ret.setReason(reason);
        ret.setCreatedBy(currentUser.usernameOrSystem());
        ret.setCreatedAt(time.now());
        issues.save(ret);
        Item item = lookup.item(itemId);
        Warehouse wh = lookup.warehouse(req.warehouseId());
        audit.log("生产余料退库", mo.getMoNo(),
                item.getName() + " " + b.getBatchNo() + "×" + plain(req.qty()) + " 退回 " + wh.getName(), reason);
        return detail(moId);
    }

    /**
     * 取消：未发料的直接取消；已发料的把物料退回原批次原仓库，前提是还没有开工。
     * 开了工的不能取消，那已经是生产事实，要走偏差处理。取消后单据和审计都保留。
     */
    @Transactional
    public MoView cancel(Long moId, CancelReq req) {
        MfgOrder mo = lock(moId);
        if (mo.getStatus() != MoStatus.PLANNED && mo.getStatus() != MoStatus.ISSUED) {
            throw new BizException(mo.getMoNo() + " 当前为" + mo.getStatus().getLabel() + "，不能取消");
        }
        String returned = "";
        if (mo.getStatus() == MoStatus.ISSUED) {
            if (mes.started(moId)) {
                throw new BizException(mo.getMoNo() + " 的批生产记录已开始填写，不能取消；异常情况请登记偏差");
            }
            if (mo.getMoType() == MoType.OEM) {
                if (oemReports.findFirstByMoIdAndStatus(moId, OemReportStatus.SUBMITTED).isPresent()) {
                    throw new BizException("受托厂已申报完工，请先退回申报再取消");
                }
                boolean producing = oemProgress.findByMoIdInOrderByIdAsc(List.of(moId)).stream()
                        .anyMatch(p -> p.getStage() == OemStage.PRODUCING || p.getStage() == OemStage.TESTING);
                if (producing) {
                    throw new BizException("受托厂已回传生产中，不能取消；异常情况请登记偏差");
                }
            }
            List<String> detail = new ArrayList<>();
            List<MfgIssue> issued = issues.findByMoIdOrderByIdAsc(moId);
            Map<String, BigDecimal> netByKey = new LinkedHashMap<>();
            Map<String, MfgIssue> sampleByKey = new LinkedHashMap<>();
            for (MfgIssue i : issued) {
                String key = i.getBatchId() + "-" + i.getWarehouseId();
                netByKey.merge(key, i.getQty(), BigDecimal::add);
                sampleByKey.putIfAbsent(key, i);
            }
            for (Map.Entry<String, BigDecimal> e : netByKey.entrySet()) {
                if (e.getValue().signum() <= 0) {
                    continue;
                }
                MfgIssue s = sampleByKey.get(e.getKey());
                Batch b = inventory.batch(s.getBatchId());
                if (mo.getOemWarehouseId() != null) {
                    inventory.change(b, mo.getOemWarehouseId(), e.getValue().negate(), TxnType.ISSUE_BACK, mo.getMoNo());
                }
                inventory.change(b, s.getWarehouseId(), e.getValue(), TxnType.ISSUE_BACK, mo.getMoNo());
                detail.add(b.getBatchNo() + "×" + plain(e.getValue()));
            }
            // 退回后这张单没有用料，追溯不能再显示这些批次流向了它；库存流水和审计里仍有记录
            issues.deleteAll(issued);
            returned = detail.isEmpty() ? "" : "，物料退回：" + String.join("，", detail);
        }
        mo.setStatus(MoStatus.CANCELLED);
        mo.setCancelReason(req.reason().trim());
        mo.setCancelledBy(currentUser.usernameOrSystem());
        mo.setCancelledAt(time.now());
        orders.save(mo);
        audit.log("取消生产单", mo.getMoNo(), lookup.item(mo.getFgItemId()).getName() + " × " + plain(mo.getPlannedQty()) + returned, mo.getCancelReason());
        return toView(mo, lookup.itemMap(), lookup.partnerMap(), Map.of());
    }

    /** 完工入库：生成成品批次（待上市放行），记录批次谱系；委外单同步核销寄存仓寄售料账。 */
    @Transactional
    public MoView complete(Long moId, CompleteReq req) {
        MfgOrder mo = lock(moId);
        if (mo.getStatus() != MoStatus.ISSUED) {
            throw new BizException(mo.getMoNo() + " 当前状态为" + mo.getStatus().getLabel() + "，不能完工入库");
        }
        LocalDate today = time.today();
        Partner factory = lookup.partner(mo.getFactoryId());
        List<String> recordGaps = mes.missing(mo.getId());
        if (!recordGaps.isEmpty()) {
            throw new BizException("批生产记录未完成，不能入库：" + String.join("；", recordGaps));
        }
        if (mo.getMoType() == MoType.OEM && !req.oemReleased()) {
            throw new BizException("委托生产须在受托厂完成出厂放行后才能入库");
        }
        Warehouse wh = lookup.warehouse(req.warehouseId());
        boolean whOk = wh.getKind() == WarehouseKind.OWN
                || (wh.getKind() == WarehouseKind.OEM && factory.getId().equals(wh.getPartnerId()));
        if (!whOk) {
            throw new BizException("成品只能入自有仓，或该受托厂的寄存仓");
        }
        LocalDate mfg = req.mfgDate() == null ? today : req.mfgDate();
        if (mfg.isAfter(today)) {
            throw new BizException("生产日期不能晚于今天");
        }

        BigDecimal ratio = req.actualQty().divide(mo.getPlannedQty(), 4, RoundingMode.HALF_UP);
        boolean outOfRange = ratio.compareTo(YIELD_MIN) < 0 || ratio.compareTo(YIELD_MAX) > 0;
        if (outOfRange && (req.balanceNote() == null || req.balanceNote().isBlank())) {
            throw new BizException("实际产量为计划的 " + ratio.movePointRight(2).stripTrailingZeros().toPlainString()
                    + "%，超出 95%–102% 的限度，请填写物料平衡说明");
        }

        List<Requirement> reqs = requirements(mo);
        Map<Long, ExtMaterialReq> ext = req.extMaterials() == null ? Map.of()
                : req.extMaterials().stream().collect(Collectors.toMap(ExtMaterialReq::itemId, Function.identity(), (a, b) -> b));
        for (Requirement r : reqs) {
            if (r.mode() == SupplyMode.OEM && !ext.containsKey(r.item().getId())) {
                throw new BizException("请登记 " + r.item().getName() + " 的受托厂原料批号");
            }
        }

        Item fg = lookup.item(mo.getFgItemId());
        String prefix = factory.getBatchPrefix() == null || factory.getBatchPrefix().isBlank()
                ? factory.getCode() : factory.getBatchPrefix();
        String batchNo = docNo.next(prefix, today);
        Batch out = inventory.createBatch(batchNo, fg, mfg, ShelfLife.expiryFrom(mfg, fg.getShelfMonths()),
                BatchStatus.PENDING_RELEASE, SourceType.MO, mo.getMoNo(), factory.getId(),
                blankToNull(req.extBatchNo()), req.actualQty());
        out.setOemReleased(mo.getMoType() == MoType.OEM);
        batches.save(out);
        inventory.change(out, wh.getId(), req.actualQty(), TxnType.PRODUCE, mo.getMoNo());

        Map<Long, List<MfgIssue>> byBatch = issues.findByMoIdOrderByIdAsc(mo.getId()).stream()
                .collect(Collectors.groupingBy(MfgIssue::getBatchId, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<Long, List<MfgIssue>> e : byBatch.entrySet()) {
            BigDecimal netQty = e.getValue().stream().map(MfgIssue::getQty).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (netQty.signum() <= 0) {
                continue;
            }
            if (mo.getOemWarehouseId() != null) {
                inventory.change(inventory.batch(e.getKey()), mo.getOemWarehouseId(), netQty.negate(), TxnType.ISSUE, mo.getMoNo());
            }
            BatchGenealogy g = new BatchGenealogy();
            g.setChildBatchId(out.getId());
            g.setParentBatchId(e.getKey());
            g.setItemId(e.getValue().get(0).getItemId());
            g.setQty(netQty);
            g.setSupplyMode(SupplyMode.BRAND);
            genealogy.save(g);
        }
        for (Requirement r : reqs) {
            if (r.mode() == SupplyMode.OEM) {
                ExtMaterialReq x = ext.get(r.item().getId());
                BatchGenealogy g = new BatchGenealogy();
                g.setChildBatchId(out.getId());
                g.setItemId(r.item().getId());
                g.setQty(x.qty());
                g.setExtBatchNo(x.extBatchNo().trim());
                g.setSupplyMode(SupplyMode.OEM);
                genealogy.save(g);
            }
        }

        mo.setStatus(MoStatus.COMPLETED);
        mo.setOutBatchId(out.getId());
        mo.setActualQty(req.actualQty());
        mo.setCompletedAt(time.now());
        orders.save(mo);
        // 生产过程中的偏差此时才有批次可挂：重大偏差没关闭，这批不能上市放行
        deviations.findByMoId(mo.getId()).forEach(d -> {
            d.setBatchId(out.getId());
            deviations.save(d);
        });
        oemReports.findFirstByMoIdAndStatus(mo.getId(), OemReportStatus.SUBMITTED).ifPresent(r -> {
            r.setStatus(OemReportStatus.ACCEPTED);
            r.setReviewedBy(currentUser.usernameOrSystem());
            r.setReviewedAt(time.now());
            oemReports.save(r);
        });
        audit.log("完工入库", batchNo, mo.getMoNo() + "，产量 " + plain(req.actualQty()) + "（计划 " + plain(mo.getPlannedQty())
                + "），入 " + wh.getName(), outOfRange ? "物料平衡说明：" + req.balanceNote().trim() : null);
        return toView(mo, lookup.itemMap(), lookup.partnerMap(), Map.of(out.getId(), batchNo));
    }

    // ---------- 内部 ----------

    private List<Requirement> requirements(MfgOrder mo) {
        Map<Long, Item> im = lookup.itemMap();
        List<Requirement> out = new ArrayList<>();
        List<BomLine> lines = mo.getFormulaVersionId() == null ? master.bomLines(mo.getFgItemId())
                : master.versionLines(mo.getFormulaVersionId());
        for (BomLine l : lines) {
            SupplyMode mode = mo.getMoType() == MoType.OWN ? SupplyMode.BRAND : l.getSupplyMode();
            BigDecimal qty = l.getQtyPer().multiply(mo.getPlannedQty()).setScale(4, RoundingMode.UP);
            out.add(new Requirement(l, im.get(l.getItemId()), mode, qty));
        }
        return out;
    }

    private List<Long> ownWarehouseIds() {
        return warehouses.findByKind(WarehouseKind.OWN).stream().map(Warehouse::getId).toList();
    }

    private MfgOrder lock(Long id) {
        return orders.lockById(id).orElseThrow(() -> BizException.notFound("生产单"));
    }

    private MfgOrder require(Long id) {
        return orders.findById(id).orElseThrow(() -> BizException.notFound("生产单"));
    }

    private MoView toView(MfgOrder m, Map<Long, Item> im, Map<Long, Partner> pm, Map<Long, String> outNos) {
        return toView(m, im, pm, outNos, null, null);
    }

    /** 维护委托生产单单位加工费与税率。已进入有效对账单的不能再修改。 */
    @Transactional
    public MoView setProcessingFee(Long moId, SetFeeReq req) {
        MfgOrder mo = lock(moId);
        if (mo.getMoType() != MoType.OEM) {
            throw new BizException("只有委托生产单需要维护加工费");
        }
        if (mo.getStatus() == MoStatus.CANCELLED) {
            throw new BizException("生产单 " + mo.getMoNo() + " 已取消");
        }
        if (statementLines.countBilledMfgOrder(mo.getId(), com.cosmetic.scm.domain.StatementStatus.CANCELLED) > 0) {
            throw new BizException("生产单 " + mo.getMoNo() + " 已进了对账单，不能改加工费。要改请先作废对账单");
        }
        String reason = blankToNull(req.reason());
        if (mo.getProcessingUnitFee() != null && reason == null) {
            throw new BizException("修改已有加工费要写明原因");
        }
        Partner f = lookup.partner(mo.getFactoryId());
        BigDecimal rate = req.processingTaxRate() != null ? req.processingTaxRate()
                : (f.getDefaultTaxRate() != null ? f.getDefaultTaxRate() : new BigDecimal("13.00"));
        String prev = mo.getProcessingUnitFee() == null ? "未录入" : plain(mo.getProcessingUnitFee());
        mo.setProcessingUnitFee(req.processingUnitFee());
        mo.setProcessingTaxRate(rate);
        orders.save(mo);
        audit.logMoney("设置委外加工费", mo.getMoNo(),
                f.getName() + " 加工费 " + prev + " → " + plain(req.processingUnitFee()) + "（税率 " + plain(rate) + "%）", reason);
        Map<Long, String> outNos = mo.getOutBatchId() == null ? Map.of()
                : batches.findById(mo.getOutBatchId()).map(b -> Map.of(b.getId(), b.getBatchNo())).orElse(Map.of());
        return toView(mo, lookup.itemMap(), lookup.partnerMap(), outNos);
    }

    private MoView toView(MfgOrder m, Map<Long, Item> im, Map<Long, Partner> pm, Map<Long, String> outNos,
                          String oemStage, OemReportStatus oemReport) {
        Item fg = im.get(m.getFgItemId());
        Partner f = pm.get(m.getFactoryId());
        boolean showPrice = com.cosmetic.scm.security.PriceAccess.allowed();
        BigDecimal yieldPct = m.getActualQty() == null ? null
                : m.getActualQty().multiply(BigDecimal.valueOf(100)).divide(m.getPlannedQty(), 1, RoundingMode.HALF_UP);
        return new MoView(m.getId(), m.getMoNo(), m.getMoType(), m.getMoType().getLabel(), m.getFactoryId(),
                f == null ? null : f.getName(), m.getFgItemId(), fg.getCode(), fg.getName(), m.getPlannedQty(),
                m.getPlannedDate(), m.getStatus(), m.getStatus().getLabel(),
                m.getOutBatchId() == null ? null : outNos.get(m.getOutBatchId()), m.getActualQty(), yieldPct,
                showPrice ? m.getProcessingUnitFee() : null, showPrice ? m.getProcessingTaxRate() : null,
                m.getCreatedBy(), m.getCreatedAt(), oemStage, oemReport, oemReport == null ? null : oemReport.getLabel(),
                m.getCancelReason(), m.getCancelledBy(), m.getCancelledAt());
    }

    private static String issueTypeLabel(String type) {
        return switch (type) {
            case "SUPPLEMENT" -> "超耗补料";
            case "RETURN" -> "余料退库";
            default -> "首次发料";
        };
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
