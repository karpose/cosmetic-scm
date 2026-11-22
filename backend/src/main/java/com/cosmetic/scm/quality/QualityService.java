package com.cosmetic.scm.quality;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.CaseStatus;
import com.cosmetic.scm.domain.Decision;
import com.cosmetic.scm.domain.DeviationLevel;
import com.cosmetic.scm.domain.FilingStatus;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.MoType;
import com.cosmetic.scm.domain.QcStage;
import com.cosmetic.scm.domain.RetainStatus;
import com.cosmetic.scm.domain.SourceType;
import com.cosmetic.scm.domain.rules.ShelfLife;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.inventory.InventoryService;
import com.cosmetic.scm.inventory.InventoryService.BatchView;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.plm.ProductFilingRepository;
import com.cosmetic.scm.production.MfgOrderRepository;
import com.cosmetic.scm.qms.Deviation;
import com.cosmetic.scm.qms.DeviationRepository;
import com.cosmetic.scm.qms.RetainSample;
import com.cosmetic.scm.qms.RetainSampleRepository;
import com.cosmetic.scm.security.AppUser;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 质量放行。物料检验放行后才能发料；成品在受托厂出厂放行的基础上，
 * 还须由品牌方质量安全负责人上市放行（GMP 第四十五、五十五条）。
 * 每次签批都需要签批人再次输入密码，签批人、时间、结论、理由全部留痕。
 */
@Service
@RequiredArgsConstructor
public class QualityService {

    @org.springframework.beans.factory.annotation.Value("${scm.compliance.require-docs-for-release:false}")
    private boolean requireComplianceDocs;

    private final com.cosmetic.scm.plm.ComplianceService compliance;
    private final QualitySpecService specs;
    private final com.cosmetic.scm.plm.ArtworkProofService artworkProofs;
    private final com.cosmetic.scm.master.MasterService master;

    private final QualityDecisionRepository decisions;
    private final com.cosmetic.scm.inventory.GenealogyRepository genealogy;
    private final com.cosmetic.scm.recall.RecallRepository recalls;
    private final BatchRepository batches;
    private final InventoryService inventory;
    private final MfgOrderRepository mfgOrders;
    private final DeviationRepository deviations;
    private final com.cosmetic.scm.supplierreturn.SupplierReturnRepository supplierReturns;
    private final RetainSampleRepository retainSamples;
    private final ProductFilingRepository filings;
    private final MasterLookup lookup;
    private final AuditService audit;
    private final TimeProvider time;

    /** artworkConfirmed：包材有已批准的设计稿时，确认印刷内容与它一致。 */
    public record MaterialDecisionReq(@NotNull Decision decision,
                                      @NotBlank(message = "请填写检验报告编号") @Size(max = 60) String reportNo,
                                      @NotBlank(message = "请填写签批说明") @Size(max = 500) String note,
                                      @NotBlank(message = "请输入签批密码") String password, boolean artworkConfirmed) {
        public MaterialDecisionReq(Decision decision, String reportNo, String note, String password) {
            this(decision, reportNo, note, password, false);
        }
    }

    public record ReleaseReq(@NotNull Decision decision,
                             @Size(max = 60) String reportNo,
                             @NotBlank(message = "请填写签批说明") @Size(max = 500) String note,
                             boolean oemReleaseReviewed,
                             boolean inspectionPassed,
                             boolean recordsComplete,
                             boolean consistentWithFiling,
                             @Min(0) Integer retainQty,
                             @Size(max = 100) String retainLocation,
                             @NotBlank(message = "请输入签批密码") String password) {
    }

    public record FreezeReq(@NotEmpty List<String> batchNos,
                            @NotBlank(message = "请填写冻结原因") @Size(max = 300) String reason,
                            @NotBlank(message = "请输入签批密码") String password) {
    }

    public record UnfreezeReq(@NotBlank(message = "请填写解冻原因") @Size(max = 300) String reason,
                              @NotBlank(message = "请输入签批密码") String password) {
    }

    public record DecisionView(Long id, Long batchId, String batchNo, String itemName, QcStage stage, String stageLabel,
                               Decision decision, String decisionLabel, String reportNo, String note, String checklist,
                               Integer retainQty, String retainLocation, LocalDate retainUntil,
                               String signedByName, LocalDateTime signedAt) {
    }

    public record QueueView(List<BatchView> quarantine, List<BatchView> pendingRelease, List<BatchView> frozen,
                            List<DecisionView> recent) {
    }

    public record QualitySummary(long quarantineCount, long pendingReleaseCount, long frozenCount,
                                 long decisions30dTotal, long material30dReleased, long material30dRejected,
                                 Double material30dPassRatePct, long market30dReleased, long market30dRejected,
                                 Double market30dPassRatePct) {
    }

    public record NcrDispositionRow(Long batchId, String batchNo, String itemCode, String itemName, String itemType,
                                    String unit, BatchStatus batchStatus, String batchStatusLabel,
                                    SourceType sourceType, String sourceNo, String partnerName,
                                    java.math.BigDecimal qtyIn, java.math.BigDecimal onHand,
                                    String reason, java.math.BigDecimal returnedQty,
                                    List<String> supplierReturnNos, int openDeviationCount, List<String> deviationNos,
                                    String closureState, String closureStateLabel, String recommendedAction) {
    }

    @Transactional(readOnly = true)
    public QueueView queue() {
        return new QueueView(
                inventory.toViews(batches.findByStatusInOrderByIdDesc(List.of(BatchStatus.QUARANTINE))),
                inventory.toViews(batches.findByStatusInOrderByIdDesc(List.of(BatchStatus.PENDING_RELEASE))),
                inventory.toViews(batches.findByStatusInOrderByIdDesc(List.of(BatchStatus.FROZEN))),
                toViews(decisions.findTop50ByOrderByIdDesc()));
    }

    @Transactional(readOnly = true)
    public List<DecisionView> listAllDecisions() {
        return toViews(decisions.findAllByOrderByIdDesc());
    }

    /** 不合格品与冻结批次处置闭环台账：关联在库结存、退供单与质量偏差，识别待处置风险。 */
    @Transactional(readOnly = true)
    public List<NcrDispositionRow> ncrDispositions() {
        List<Batch> list = batches.findByStatusInOrderByIdDesc(List.of(BatchStatus.REJECTED, BatchStatus.FROZEN));
        if (list.isEmpty()) {
            return List.of();
        }
        List<BatchView> views = inventory.toViews(list);
        List<Long> batchIds = list.stream().map(Batch::getId).toList();
        Map<Long, List<Deviation>> devByBatch = deviations.findByBatchIdIn(batchIds).stream()
                .collect(Collectors.groupingBy(Deviation::getBatchId));
        Map<Long, List<com.cosmetic.scm.supplierreturn.SupplierReturn>> retByBatch = supplierReturns
                .findByBatchIdInOrderByIdDesc(batchIds).stream()
                .filter(r -> r.getStatus() != com.cosmetic.scm.domain.SupplierReturnStatus.CANCELLED)
                .collect(Collectors.groupingBy(com.cosmetic.scm.supplierreturn.SupplierReturn::getBatchId));
        List<NcrDispositionRow> rows = new ArrayList<>();
        for (BatchView v : views) {
            List<Deviation> devs = devByBatch.getOrDefault(v.id(), List.of());
            int openDevs = (int) devs.stream().filter(d -> d.getStatus() == CaseStatus.OPEN).count();
            List<String> devNos = devs.stream().map(Deviation::getDeviationNo).toList();
            List<com.cosmetic.scm.supplierreturn.SupplierReturn> rets = retByBatch.getOrDefault(v.id(), List.of());
            java.math.BigDecimal retQty = rets.stream().map(com.cosmetic.scm.supplierreturn.SupplierReturn::getQty)
                    .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
            List<String> retNos = rets.stream().map(com.cosmetic.scm.supplierreturn.SupplierReturn::getReturnNo).toList();
            String reason = v.freezeReason();
            if (reason == null || reason.isBlank()) {
                reason = decisions.findByBatchIdOrderByIdDesc(v.id()).stream()
                        .filter(d -> d.getDecision() == Decision.REJECTED)
                        .map(QualityDecision::getNote).findFirst().orElse(null);
            }
            boolean hasStock = v.onHand() != null && v.onHand().signum() > 0;
            String state;
            String stateLabel;
            String action;
            if (!hasStock && openDevs == 0) {
                state = "CLOSED";
                stateLabel = "已清零闭环";
                action = "已闭环";
            } else if (!hasStock) {
                state = "IN_PROGRESS";
                stateLabel = "待关偏差";
                action = "跟进关闭关联质量偏差";
            } else if (openDevs > 0 || !retNos.isEmpty()) {
                state = "IN_PROGRESS";
                stateLabel = "处置中";
                action = v.status() == BatchStatus.FROZEN ? "调查后解冻或判不合格" : (v.sourceType() == SourceType.PO ? "跟进退供或报废清零" : "跟进偏差并报废清零");
            } else {
                state = "PENDING";
                stateLabel = "待处置";
                action = v.status() == BatchStatus.FROZEN ? "核查冻结原因并登记偏差" : (v.sourceType() == SourceType.PO ? "发起退供或报废清零" : "登记偏差并评估报废");
            }
            rows.add(new NcrDispositionRow(v.id(), v.batchNo(), v.itemCode(), v.itemName(), v.itemType(),
                    v.unit(), v.status(), v.statusLabel(), v.sourceType(), v.sourceNo(), v.partnerName(),
                    v.qtyIn(), v.onHand(), reason, retQty, retNos, openDevs, devNos, state, stateLabel, action));
        }
        return rows;
    }

    @Transactional(readOnly = true)
    public QualitySummary summary() {
        long qCount = batches.countByStatus(BatchStatus.QUARANTINE);
        long prCount = batches.countByStatus(BatchStatus.PENDING_RELEASE);
        long frCount = batches.countByStatus(BatchStatus.FROZEN);
        List<QualityDecision> recent30 = decisions.findBySignedAtGreaterThanEqual(time.now().minusDays(30));
        long matRel = recent30.stream().filter(d -> d.getStage() == QcStage.MATERIAL_QC && d.getDecision() == Decision.RELEASED).count();
        long matRej = recent30.stream().filter(d -> d.getStage() == QcStage.MATERIAL_QC && d.getDecision() == Decision.REJECTED).count();
        long mktRel = recent30.stream().filter(d -> d.getStage() == QcStage.MARKET_RELEASE && d.getDecision() == Decision.RELEASED).count();
        long mktRej = recent30.stream().filter(d -> d.getStage() == QcStage.MARKET_RELEASE && d.getDecision() == Decision.REJECTED).count();
        Double matRate = (matRel + matRej) == 0 ? null : Math.round(matRel * 1000.0 / (matRel + matRej)) / 10.0;
        Double mktRate = (mktRel + mktRej) == 0 ? null : Math.round(mktRel * 1000.0 / (mktRel + mktRej)) / 10.0;
        return new QualitySummary(qCount, prCount, frCount, recent30.size(), matRel, matRej, matRate, mktRel, mktRej, mktRate);
    }

    @Transactional(readOnly = true)
    public List<DecisionView> decisionsOf(Long batchId) {
        return toViews(decisions.findByBatchIdOrderByIdDesc(batchId));
    }

    /** 物料（原料、包材）检验放行或拒收。 */
    @Transactional
    public BatchView materialDecision(Long batchId, MaterialDecisionReq req, AppUser signer) {
        Batch b = lockBatch(batchId);
        Item item = lookup.item(b.getItemId());
        if (item.getItemType() == ItemType.FG) {
            throw new BizException("成品批次请走上市放行");
        }
        if (b.getStatus() != BatchStatus.QUARANTINE) {
            throw new BizException("批次 " + b.getBatchNo() + " 当前为" + b.getStatus().getLabel() + "，不是待检状态");
        }
        if (req.decision() == Decision.RELEASED && ShelfLife.isExpired(b.getExpDate(), time.today())) {
            throw new BizException("批次 " + b.getBatchNo() + " 已过有效期，不能放行");
        }
        String artworkCheck = null;
        if (req.decision() == Decision.RELEASED) {
            specs.blocker(b).ifPresent(m -> {
                throw new BizException(m);
            });
            requireNoOpenDeviation(b);
            var printCheck = artworkProofs.checkForRelease(b, item, req.artworkConfirmed());
            if (printCheck != null) {
                artworkCheck = printCheck.text();
                b.setArtworkId(printCheck.artworkId());
                b.setProofId(printCheck.proofId());
            }
        }
        b.setStatus(req.decision() == Decision.RELEASED ? BatchStatus.RELEASED : BatchStatus.REJECTED);
        batches.save(b);
        QualityDecision d = newDecision(b, QcStage.MATERIAL_QC, req.decision(), req.reportNo(), req.note(), signer);
        d.setChecklist(artworkCheck);
        decisions.save(d);
        audit.log(req.decision() == Decision.RELEASED ? "物料检验放行" : "物料检验拒收", b.getBatchNo(),
                item.getName() + "，报告 " + req.reportNo().trim() + (artworkCheck == null ? "" : "，" + artworkCheck), req.note().trim());
        return single(b);
    }

    /** 成品上市放行或不予放行。 */
    @Transactional
    public BatchView marketRelease(Long batchId, ReleaseReq req, AppUser signer) {
        Batch b = lockBatch(batchId);
        Item item = lookup.item(b.getItemId());
        if (item.getItemType() != ItemType.FG) {
            throw new BizException("只有成品批次需要上市放行");
        }
        if (b.getStatus() != BatchStatus.PENDING_RELEASE) {
            throw new BizException("批次 " + b.getBatchNo() + " 当前为" + b.getStatus().getLabel() + "，不是待上市放行状态");
        }
        boolean oem = b.getSourceType() == SourceType.MO && mfgOrders.findByMoNo(b.getSourceNo())
                .map(m -> m.getMoType() == MoType.OEM).orElse(false);

        QualityDecision d = newDecision(b, QcStage.MARKET_RELEASE, req.decision(), req.reportNo(), req.note(), signer);
        if (req.decision() == Decision.RELEASED) {
            List<String> missing = new ArrayList<>();
            if (oem && !(req.oemReleaseReviewed() && b.isOemReleased())) {
                missing.add("受托厂出厂放行记录已审核");
            }
            // 合规资料缺失默认只提醒，开关打开后才拦：一上线就拦的话，所有现存产品都放不了行
            if (requireComplianceDocs) {
                Long versionId = b.getSourceType() == SourceType.MO ? mfgOrders.findByMoNo(b.getSourceNo()).map(com.cosmetic.scm.production.MfgOrder::getFormulaVersionId).orElse(null) : null;
                if (versionId == null) {
                    versionId = master.effectiveVersionId(item.getId());
                }
                compliance.missingFor(item.getId(), versionId).forEach(t -> missing.add("该配方版本的" + t + "已签批"));
            }
            if (!req.inspectionPassed()) {
                missing.add("出厂检验合格");
            }
            specs.blocker(b).ifPresent(missing::add);
            if (!req.recordsComplete()) {
                missing.add("批生产记录完整、物料平衡在限度内");
            }
            if (!req.consistentWithFiling()) {
                missing.add("配方、工艺、标签与注册备案资料一致");
            }
            if (!missing.isEmpty()) {
                throw new BizException("放行检查未完成：" + String.join("；", missing));
            }
            if (item.getFilingNo() == null || item.getFilingNo().isBlank()) {
                throw new BizException(item.getName() + " 还没有登记备案或注册编号，不能上市放行");
            }
            requireNoOpenDeviation(b);
            requireMaterialsUsable(b);
            // 只看最新一条已完成的记录：注册证续期后，旧证的到期日不再有效
            filings.findByFgItemIdAndStatusOrderByIdDesc(item.getId(), FilingStatus.FILED).stream().findFirst()
                    .filter(f -> f.getExpiryDate() != null && f.getExpiryDate().isBefore(time.today()))
                    .ifPresent(f -> {
                        throw new BizException(item.getName() + " 的注册证 " + f.getFilingNo() + " 已于 " + f.getExpiryDate() + " 到期，不能上市放行");
                    });
            if (req.reportNo() == null || req.reportNo().isBlank()) {
                throw new BizException("请填写出厂检验报告编号");
            }
            if (req.retainQty() == null || req.retainQty() <= 0 || req.retainLocation() == null || req.retainLocation().isBlank()) {
                throw new BizException("请登记留样数量和留样地点（每批出厂产品均应留样）");
            }
            if (ShelfLife.isExpired(b.getExpDate(), time.today())) {
                throw new BizException("批次已过有效期，不能放行");
            }
            d.setChecklist((oem ? "受托厂放行已审核；" : "") + "出厂检验合格；批记录完整；与备案一致");
            d.setRetainQty(req.retainQty());
            d.setRetainLocation(req.retainLocation().trim());
            d.setRetainUntil(ShelfLife.retainUntil(b.getExpDate()));
            b.setStatus(BatchStatus.RELEASED);
            RetainSample sample = new RetainSample();
            sample.setBatchId(b.getId());
            sample.setQty(d.getRetainQty());
            sample.setRemainingQty(d.getRetainQty());
            sample.setLocation(d.getRetainLocation());
            sample.setRetainUntil(d.getRetainUntil());
            sample.setStatus(RetainStatus.HELD);
            sample.setCreatedBy(signer.getDisplayName());
            sample.setCreatedAt(time.now());
            retainSamples.save(sample);
        } else {
            b.setStatus(BatchStatus.REJECTED);
        }
        batches.save(b);
        decisions.save(d);
        String detail = item.getName() + (d.getReportNo() == null ? "" : "，报告 " + d.getReportNo())
                + (d.getRetainQty() == null ? "" : "，留样 " + d.getRetainQty() + " 件存于 " + d.getRetainLocation()
                + "，保存至 " + d.getRetainUntil());
        audit.log(req.decision() == Decision.RELEASED ? "成品上市放行" : "成品不予放行", b.getBatchNo(), detail, req.note().trim());
        return single(b);
    }

    /** 冻结：已冻结或不合格的批次跳过。返回实际冻结的批号。 */
    @Transactional
    public List<String> freeze(FreezeReq req, AppUser signer) {
        List<String> done = new ArrayList<>();
        for (String no : req.batchNos()) {
            Batch b = batches.lockByBatchNo(no == null ? null : no.trim()).orElseThrow(() -> BizException.notFound("批次 " + no + " "));
            if (b.getStatus() == BatchStatus.FROZEN || b.getStatus() == BatchStatus.REJECTED) {
                continue;
            }
            b.setStatusBeforeFreeze(b.getStatus());
            b.setStatus(BatchStatus.FROZEN);
            b.setFreezeReason(req.reason().trim());
            batches.save(b);
            done.add(b.getBatchNo());
        }
        if (done.isEmpty()) {
            throw new BizException("所选批次都已冻结或已判定不合格");
        }
        audit.log("冻结批次", String.join("、", done), "签批人 " + signer.getDisplayName(), req.reason().trim());
        return done;
    }

    @Transactional
    public BatchView unfreeze(Long batchId, UnfreezeReq req, AppUser signer) {
        Batch b = lockBatch(batchId);
        if (b.getStatus() != BatchStatus.FROZEN) {
            throw new BizException("批次 " + b.getBatchNo() + " 没有被冻结");
        }
        // 召回没结束就解冻，已召回的批次又能发货了
        if (recalls.existsByBatchIdAndStatus(b.getId(), com.cosmetic.scm.domain.CaseStatus.OPEN)) {
            throw new BizException("批次 " + b.getBatchNo() + " 有进行中的召回，要先关闭召回才能解冻");
        }
        BatchStatus back = b.getStatusBeforeFreeze() == null ? BatchStatus.QUARANTINE : b.getStatusBeforeFreeze();
        b.setStatus(back);
        b.setStatusBeforeFreeze(null);
        b.setFreezeReason(null);
        batches.save(b);
        audit.log("解冻批次", b.getBatchNo(), "恢复为" + back.getLabel() + "，签批人 " + signer.getDisplayName(), req.reason().trim());
        return single(b);
    }

    private Batch lockBatch(Long id) {
        return batches.lockById(id).orElseThrow(() -> BizException.notFound("批次"));
    }

    /** 微小偏差不挡放行；重大及以上的必须先关闭。 */
    /** 原料被冻结后，已发料的单子照样能完工。成品放行这一关要拦住，否则问题原料做的成品还是会上市。 */
    private void requireMaterialsUsable(Batch fg) {
        List<Long> parents = genealogy.findByChildBatchId(fg.getId()).stream()
                .map(com.cosmetic.scm.inventory.BatchGenealogy::getParentBatchId).filter(java.util.Objects::nonNull).distinct().toList();
        List<String> bad = batches.findAllById(parents).stream()
                .filter(p -> p.getStatus() == BatchStatus.FROZEN || p.getStatus() == BatchStatus.REJECTED)
                .map(p -> p.getBatchNo() + "（" + p.getStatus().getLabel() + "）").toList();
        if (!bad.isEmpty()) {
            throw new BizException("用到的物料批次 " + String.join("、", bad) + " 有问题，不能放行；调查清楚并解冻后再放行，或判定不合格");
        }
    }

    /**
     * 有已批准设计稿的包材，放行前要确认印刷内容与它一致；设计稿对应的配方已变，印刷的成分表可能不对，不能放行。
     * 没有设计稿的包材照旧。返回写进放行记录的核对说明。
     */
    private void requireNoOpenDeviation(Batch b) {
        List<String> open = deviations.findByBatchIdAndStatus(b.getId(), CaseStatus.OPEN).stream()
                .filter(x -> x.getLevel() != DeviationLevel.MINOR).map(Deviation::getDeviationNo).toList();
        if (!open.isEmpty()) {
            throw new BizException("批次 " + b.getBatchNo() + " 有未关闭的偏差：" + String.join("、", open) + "，关闭后才能放行");
        }
    }

    private QualityDecision newDecision(Batch b, QcStage stage, Decision decision, String reportNo, String note,
                                        AppUser signer) {
        QualityDecision d = new QualityDecision();
        d.setBatchId(b.getId());
        d.setStage(stage);
        d.setDecision(decision);
        d.setReportNo(reportNo == null || reportNo.isBlank() ? null : reportNo.trim());
        d.setNote(note.trim());
        d.setSignedById(signer.getId());
        d.setSignedByName(signer.getDisplayName());
        d.setSignedAt(time.now());
        return d;
    }

    private BatchView single(Batch b) {
        return inventory.toViews(List.of(b)).get(0);
    }

    private List<DecisionView> toViews(List<QualityDecision> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, Batch> bm = batches.findAllById(list.stream().map(QualityDecision::getBatchId).distinct().toList())
                .stream().collect(Collectors.toMap(Batch::getId, Function.identity()));
        Map<Long, Item> im = lookup.itemMap();
        return list.stream().map(d -> {
            Batch b = bm.get(d.getBatchId());
            Item i = im.get(b.getItemId());
            return new DecisionView(d.getId(), b.getId(), b.getBatchNo(), i.getName(), d.getStage(), d.getStage().getLabel(),
                    d.getDecision(), d.getDecision().getLabel(), d.getReportNo(), d.getNote(), d.getChecklist(),
                    d.getRetainQty(), d.getRetainLocation(), d.getRetainUntil(), d.getSignedByName(), d.getSignedAt());
        }).toList();
    }
}
