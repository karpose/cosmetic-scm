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
 * 品牌供料部分按 FEFO 从自有仓发料；工厂代采部分在完工时登记受托厂的原料批号。
 * 完工批次一律进入“待上市放行”。
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

    public record PickView(Long batchId, String batchNo, Long warehouseId, String warehouseName, BigDecimal qty,
                           LocalDate expDate) {
    }

    public record RequirementView(Long itemId, String itemCode, String itemName, String unit, SupplyMode supplyMode,
                                  String supplyLabel, BigDecimal requiredQty, BigDecimal issuedQty,
                                  List<PickView> picks, BigDecimal shortQty, String extBatchNo, BigDecimal extQty) {
    }

    public record MoView(Long id, String moNo, MoType moType, String moTypeLabel, Long factoryId, String factoryName,
                         Long fgItemId, String fgCode, String fgName, BigDecimal plannedQty, LocalDate plannedDate,
                         MoStatus status, String statusLabel, String outBatchNo, BigDecimal actualQty,
                         BigDecimal yieldPct, String createdBy, LocalDateTime createdAt,
                         String oemStageLabel, OemReportStatus oemReportStatus, String oemReportStatusLabel,
                         String cancelReason, String cancelledBy, LocalDateTime cancelledAt) {
    }

    public record MoDetail(MoView mo, List<RequirementView> requirements, Integer formulaVersionNo) {
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

    /** 生产单详情。已计划的单据给出 FEFO 发料预览；已发料的给出实际发料批次。 */
    @Transactional(readOnly = true)
    public MoDetail detail(Long moId) {
        MfgOrder mo = require(moId);
        Map<Long, Item> im = lookup.itemMap();
        Map<Long, Partner> pm = lookup.partnerMap();
        Map<Long, Warehouse> wm = lookup.warehouseMap();
        List<MfgIssue> done = issues.findByMoId(moId);
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
                    for (MfgIssue i : done) {
                        if (i.getItemId().equals(r.item().getId())) {
                            Batch b = issuedBatches.get(i.getBatchId());
                            picks.add(new PickView(b.getId(), b.getBatchNo(), i.getWarehouseId(),
                                    wm.get(i.getWarehouseId()).getName(), i.getQty(), b.getExpDate()));
                            issued = issued.add(i.getQty());
                        }
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
        Map<Long, String> outNos = mo.getOutBatchId() == null ? Map.of()
                : Map.of(mo.getOutBatchId(), inventory.batch(mo.getOutBatchId()).getBatchNo());
        Integer versionNo = mo.getFormulaVersionId() == null ? null
                : formulaVersions.findById(mo.getFormulaVersionId()).map(FormulaVersion::getVersionNo).orElse(null);
        return new MoDetail(toView(mo, im, pm, outNos), reqs, versionNo);
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

    /** 品牌供料部分按 FEFO 从自有仓发料。任何一种物料不足，整单不发。 */
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
        List<String> detail = new ArrayList<>();
        for (Map.Entry<Requirement, FefoAllocator.Result> e : plan.entrySet()) {
            inventory.consume(e.getValue().picks(), TxnType.ISSUE, mo.getMoNo());
            for (FefoAllocator.Pick p : e.getValue().picks()) {
                MfgIssue i = new MfgIssue();
                i.setMoId(mo.getId());
                i.setBatchId(p.batchId());
                i.setWarehouseId(p.warehouseId());
                i.setItemId(e.getKey().item().getId());
                i.setQty(p.qty());
                issues.save(i);
                detail.add(p.batchNo() + "×" + plain(p.qty()));
            }
        }
        mo.setStatus(MoStatus.ISSUED);
        mo.setIssuedAt(time.now());
        orders.save(mo);
        mes.createRecord(mo);
        audit.log("生产发料", mo.getMoNo(), detail.isEmpty() ? "无品牌供料物料" : String.join("，", detail));
        return toView(mo, lookup.itemMap(), lookup.partnerMap(), Map.of());
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
            List<MfgIssue> issued = issues.findByMoId(moId);
            for (MfgIssue i : issued) {
                Batch b = inventory.batch(i.getBatchId());
                inventory.change(b, i.getWarehouseId(), i.getQty(), TxnType.ISSUE_BACK, mo.getMoNo());
                detail.add(b.getBatchNo() + "×" + plain(i.getQty()));
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

    /** 完工入库：生成成品批次（待上市放行），记录批次谱系。 */
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

        Map<Long, List<MfgIssue>> byBatch = issues.findByMoId(mo.getId()).stream()
                .collect(Collectors.groupingBy(MfgIssue::getBatchId));
        for (Map.Entry<Long, List<MfgIssue>> e : byBatch.entrySet()) {
            BatchGenealogy g = new BatchGenealogy();
            g.setChildBatchId(out.getId());
            g.setParentBatchId(e.getKey());
            g.setItemId(e.getValue().get(0).getItemId());
            g.setQty(e.getValue().stream().map(MfgIssue::getQty).reduce(BigDecimal.ZERO, BigDecimal::add));
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

    private MoView toView(MfgOrder m, Map<Long, Item> im, Map<Long, Partner> pm, Map<Long, String> outNos,
                          String oemStage, OemReportStatus oemReport) {
        Item fg = im.get(m.getFgItemId());
        Partner f = pm.get(m.getFactoryId());
        BigDecimal yieldPct = m.getActualQty() == null ? null
                : m.getActualQty().multiply(BigDecimal.valueOf(100)).divide(m.getPlannedQty(), 1, RoundingMode.HALF_UP);
        return new MoView(m.getId(), m.getMoNo(), m.getMoType(), m.getMoType().getLabel(), m.getFactoryId(),
                f == null ? null : f.getName(), m.getFgItemId(), fg.getCode(), fg.getName(), m.getPlannedQty(),
                m.getPlannedDate(), m.getStatus(), m.getStatus().getLabel(),
                m.getOutBatchId() == null ? null : outNos.get(m.getOutBatchId()), m.getActualQty(), yieldPct,
                m.getCreatedBy(), m.getCreatedAt(), oemStage, oemReport, oemReport == null ? null : oemReport.getLabel(),
                m.getCancelReason(), m.getCancelledBy(), m.getCancelledAt());
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
