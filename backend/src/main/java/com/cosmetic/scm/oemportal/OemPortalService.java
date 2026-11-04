package com.cosmetic.scm.oemportal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.MoStatus;
import com.cosmetic.scm.domain.MoType;
import com.cosmetic.scm.domain.OemReportStatus;
import com.cosmetic.scm.domain.OemStage;
import com.cosmetic.scm.domain.SupplyMode;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.production.MfgOrder;
import com.cosmetic.scm.production.MfgOrderRepository;
import com.cosmetic.scm.production.ProductionService;
import com.cosmetic.scm.production.ProductionService.RequirementView;
import com.cosmetic.scm.security.CurrentUser;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 代工厂协同门户：受托厂账号只看到委托给本厂的单子，可以回传进度、提交完工与出厂放行申报。
 * 成品入库仍由品牌方完成（库存进品牌方的仓），入库时采纳申报的数据；不合格的申报可以退回重报。
 */
@Service
@RequiredArgsConstructor
public class OemPortalService {

    private final MfgOrderRepository orders;
    private final OemProgressRepository progress;
    private final OemReportRepository reports;
    private final OemReportMaterialRepository reportMaterials;
    private final ProductionService production;
    private final MasterLookup lookup;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    public record ProgressReq(@NotNull OemStage stage, LocalDate promiseDate, @Size(max = 300) String note) {
    }

    public record MaterialReq(@NotNull Long itemId,
                              @NotBlank(message = "请填写代采原料批号") @Size(max = 60) String extBatchNo,
                              @NotNull @Positive BigDecimal qty) {
    }

    public record ReportReq(@NotNull @Positive BigDecimal actualQty,
                            @NotNull(message = "请选择生产日期") LocalDate mfgDate,
                            @NotBlank(message = "请填写受托厂成品批号") @Size(max = 60) String extBatchNo,
                            @NotBlank(message = "请填写出厂检验报告编号") @Size(max = 60) String releaseReportNo,
                            @NotBlank(message = "请填写质量放行人") @Size(max = 64) String releasedBy,
                            @Size(max = 300) String balanceNote,
                            @Valid List<MaterialReq> materials) {
    }

    public record ReturnReq(@NotBlank(message = "请写明退回原因") @Size(max = 300) String note) {
    }

    public record ProgressView(Long id, OemStage stage, String stageLabel, LocalDate promiseDate, String note,
                               String createdBy, LocalDateTime createdAt) {
    }

    public record MaterialView(Long itemId, String itemName, String unit, String extBatchNo, BigDecimal qty) {
    }

    public record ReportView(Long id, OemReportStatus status, String statusLabel, BigDecimal actualQty,
                             LocalDate mfgDate, String extBatchNo, String releaseReportNo, String releasedBy,
                             String balanceNote, List<MaterialView> materials, String submittedBy,
                             LocalDateTime submittedAt, String reviewedBy, LocalDateTime reviewedAt, String reviewNote) {
    }

    /** 门户看到的委托单：只有本厂需要的信息。 */
    public record OemOrderView(Long id, String moNo, String fgCode, String fgName, BigDecimal plannedQty,
                               LocalDate plannedDate, MoStatus status, String statusLabel, String outBatchNo,
                               OemStage stage, String stageLabel, LocalDate promiseDate,
                               List<RequirementView> requirements, List<ProgressView> progress,
                               List<ReportView> reports) {
    }

    /** 品牌方在生产页看到的协同记录。 */
    public record OemTrail(List<ProgressView> progress, List<ReportView> reports) {
    }

    // ---------- 门户 ----------

    @Transactional(readOnly = true)
    public List<OemOrderView> myOrders() {
        Long factoryId = currentUser.portalPartnerId();
        List<MfgOrder> list = orders.findByFactoryIdAndMoTypeOrderByIdDesc(factoryId, MoType.OEM);
        Map<Long, List<ProgressView>> pm = progressOf(list.stream().map(MfgOrder::getId).toList());
        Map<Long, List<ReportView>> rm = reportsOf(list.stream().map(MfgOrder::getId).toList());
        List<OemOrderView> out = new ArrayList<>();
        for (MfgOrder m : list) {
            ProductionService.MoDetail d = production.detail(m.getId());
            List<ProgressView> pv = pm.getOrDefault(m.getId(), List.of());
            ProgressView lastStage = pv.stream().filter(p -> p.stage() != OemStage.NOTE).reduce((a, b) -> b).orElse(null);
            LocalDate promise = pv.stream().map(ProgressView::promiseDate).filter(java.util.Objects::nonNull)
                    .reduce((a, b) -> b).orElse(null);
            out.add(new OemOrderView(m.getId(), m.getMoNo(), d.mo().fgCode(), d.mo().fgName(), m.getPlannedQty(),
                    m.getPlannedDate(), m.getStatus(), m.getStatus().getLabel(), d.mo().outBatchNo(),
                    lastStage == null ? null : lastStage.stage(), lastStage == null ? "待接单" : lastStage.stageLabel(),
                    promise, d.requirements(), pv, rm.getOrDefault(m.getId(), List.of())));
        }
        return out;
    }

    @Transactional
    public OemOrderView addProgress(Long moId, ProgressReq req) {
        MfgOrder mo = mine(moId);
        if (mo.getStatus() == MoStatus.COMPLETED || mo.getStatus() == MoStatus.CANCELLED) {
            throw new BizException(mo.getMoNo() + " 已" + (mo.getStatus() == MoStatus.COMPLETED ? "完工入库" : "取消") + "，不再回传进度");
        }
        if (req.stage() == OemStage.NOTE && (req.note() == null || req.note().isBlank())) {
            throw new BizException("请填写备注内容");
        }
        if (req.promiseDate() != null && req.promiseDate().isBefore(time.today())) {
            throw new BizException("承诺交期不能早于今天");
        }
        OemProgress p = new OemProgress();
        p.setMoId(mo.getId());
        p.setStage(req.stage());
        p.setPromiseDate(req.promiseDate());
        p.setNote(blankToNull(req.note()));
        p.setCreatedBy(currentUser.usernameOrSystem());
        p.setCreatedAt(time.now());
        progress.save(p);
        audit.log("受托厂回传进度", mo.getMoNo(), req.stage().getLabel()
                + (req.promiseDate() == null ? "" : "，承诺交期 " + req.promiseDate()), p.getNote());
        return single(mo);
    }

    /** 提交完工与出厂放行申报。校验口径和品牌方完工入库一致，避免申报了却入不了库。 */
    @Transactional
    public OemOrderView submitReport(Long moId, ReportReq req) {
        MfgOrder mo = mine(moId);
        if (mo.getStatus() != MoStatus.ISSUED) {
            throw new BizException(mo.getStatus() == MoStatus.PLANNED
                    ? "品牌方还没有发料，暂不能申报完工" : mo.getMoNo() + " 已完工入库");
        }
        if (reports.findFirstByMoIdAndStatus(mo.getId(), OemReportStatus.SUBMITTED).isPresent()) {
            throw new BizException("已有一份申报等待品牌方确认，请勿重复提交");
        }
        if (req.mfgDate().isAfter(time.today())) {
            throw new BizException("生产日期不能晚于今天");
        }
        BigDecimal ratio = req.actualQty().divide(mo.getPlannedQty(), 4, RoundingMode.HALF_UP);
        boolean outOfRange = ratio.compareTo(ProductionService.YIELD_MIN) < 0 || ratio.compareTo(ProductionService.YIELD_MAX) > 0;
        if (outOfRange && (req.balanceNote() == null || req.balanceNote().isBlank())) {
            throw new BizException("实际产量为计划的 " + ratio.movePointRight(2).stripTrailingZeros().toPlainString()
                    + "%，超出 95%–102% 的限度，请填写物料平衡说明");
        }
        List<MaterialReq> mats = req.materials() == null ? List.of() : req.materials();
        Set<Long> given = mats.stream().map(MaterialReq::itemId).collect(Collectors.toSet());
        for (RequirementView r : production.detail(mo.getId()).requirements()) {
            if (r.supplyMode() == SupplyMode.OEM && !given.contains(r.itemId())) {
                throw new BizException("请登记代采原料 " + r.itemName() + " 的批号和用量");
            }
        }

        OemReport rep = new OemReport();
        rep.setMoId(mo.getId());
        rep.setStatus(OemReportStatus.SUBMITTED);
        rep.setActualQty(req.actualQty());
        rep.setMfgDate(req.mfgDate());
        rep.setExtBatchNo(req.extBatchNo().trim());
        rep.setReleaseReportNo(req.releaseReportNo().trim());
        rep.setReleasedBy(req.releasedBy().trim());
        rep.setBalanceNote(blankToNull(req.balanceNote()));
        rep.setSubmittedBy(currentUser.usernameOrSystem());
        rep.setSubmittedAt(time.now());
        reports.save(rep);
        for (MaterialReq m : mats) {
            OemReportMaterial rm = new OemReportMaterial();
            rm.setReportId(rep.getId());
            rm.setItemId(lookup.item(m.itemId()).getId());
            rm.setExtBatchNo(m.extBatchNo().trim());
            rm.setQty(m.qty());
            reportMaterials.save(rm);
        }
        audit.log("受托厂完工申报", mo.getMoNo(), "产量 " + plain(req.actualQty()) + "，成品批号 " + rep.getExtBatchNo()
                + "，出厂报告 " + rep.getReleaseReportNo() + "，放行人 " + rep.getReleasedBy(), rep.getBalanceNote());
        return single(mo);
    }

    // ---------- 品牌方 ----------

    @Transactional(readOnly = true)
    public OemTrail trail(Long moId) {
        List<Long> ids = List.of(moId);
        return new OemTrail(progressOf(ids).getOrDefault(moId, List.of()), reportsOf(ids).getOrDefault(moId, List.of()));
    }

    @Transactional
    public OemTrail returnReport(Long moId, ReturnReq req) {
        OemReport rep = reports.findFirstByMoIdAndStatus(moId, OemReportStatus.SUBMITTED)
                .orElseThrow(() -> new BizException("没有待确认的受托厂申报"));
        rep.setStatus(OemReportStatus.RETURNED);
        rep.setReviewedBy(currentUser.usernameOrSystem());
        rep.setReviewedAt(time.now());
        rep.setReviewNote(req.note().trim());
        reports.save(rep);
        audit.log("退回受托厂申报", orders.findById(moId).map(MfgOrder::getMoNo).orElse(""), "", rep.getReviewNote());
        return trail(moId);
    }

    // ---------- 内部 ----------

    /** 只能操作委托给本厂的委托单；别人的单子一律当作不存在。 */
    private MfgOrder mine(Long moId) {
        Long factoryId = currentUser.portalPartnerId();
        return orders.findById(moId)
                .filter(m -> m.getMoType() == MoType.OEM && factoryId.equals(m.getFactoryId()))
                .orElseThrow(() -> BizException.notFound("委托单"));
    }

    private OemOrderView single(MfgOrder mo) {
        return myOrders().stream().filter(o -> o.id().equals(mo.getId())).findFirst()
                .orElseThrow(() -> BizException.notFound("委托单"));
    }

    private Map<Long, List<ProgressView>> progressOf(List<Long> moIds) {
        if (moIds.isEmpty()) {
            return Map.of();
        }
        return progress.findByMoIdInOrderByIdAsc(moIds).stream().collect(Collectors.groupingBy(OemProgress::getMoId,
                Collectors.mapping(p -> new ProgressView(p.getId(), p.getStage(), p.getStage().getLabel(), p.getPromiseDate(),
                        p.getNote(), p.getCreatedBy(), p.getCreatedAt()), Collectors.toList())));
    }

    private Map<Long, List<ReportView>> reportsOf(List<Long> moIds) {
        if (moIds.isEmpty()) {
            return Map.of();
        }
        List<OemReport> list = reports.findByMoIdInOrderByIdAsc(moIds);
        if (list.isEmpty()) {
            return Map.of();
        }
        Map<Long, Item> im = lookup.itemMap();
        Map<Long, List<MaterialView>> mm = reportMaterials.findByReportIdIn(list.stream().map(OemReport::getId).toList())
                .stream().collect(Collectors.groupingBy(OemReportMaterial::getReportId, Collectors.mapping(m -> {
                    Item i = im.get(m.getItemId());
                    return new MaterialView(i.getId(), i.getName(), i.getUnit(), m.getExtBatchNo(), m.getQty());
                }, Collectors.toList())));
        return list.stream().collect(Collectors.groupingBy(OemReport::getMoId, Collectors.mapping(r -> new ReportView(
                r.getId(), r.getStatus(), r.getStatus().getLabel(), r.getActualQty(), r.getMfgDate(), r.getExtBatchNo(),
                r.getReleaseReportNo(), r.getReleasedBy(), r.getBalanceNote(), mm.getOrDefault(r.getId(), List.of()),
                r.getSubmittedBy(), r.getSubmittedAt(), r.getReviewedBy(), r.getReviewedAt(), r.getReviewNote()),
                Collectors.toList())));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
