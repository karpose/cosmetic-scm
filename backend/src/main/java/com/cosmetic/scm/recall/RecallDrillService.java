package com.cosmetic.scm.recall;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.DocNoService;
import com.cosmetic.scm.common.Qty;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.CapaSource;
import com.cosmetic.scm.domain.CapaType;
import com.cosmetic.scm.domain.DrillResult;
import com.cosmetic.scm.domain.DrillStatus;
import com.cosmetic.scm.domain.DrillTarget;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.MoType;
import com.cosmetic.scm.domain.SourceType;
import com.cosmetic.scm.domain.TxnType;
import com.cosmetic.scm.domain.rules.RecallDrillRule;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.inventory.Stock;
import com.cosmetic.scm.inventory.StockRepository;
import com.cosmetic.scm.inventory.StockTxn;
import com.cosmetic.scm.inventory.StockTxnRepository;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.Warehouse;
import com.cosmetic.scm.production.MfgOrder;
import com.cosmetic.scm.production.MfgOrderRepository;
import com.cosmetic.scm.qms.Capa;
import com.cosmetic.scm.qms.CapaRepository;
import com.cosmetic.scm.qms.DeviationService;
import com.cosmetic.scm.sales.SalesOrder;
import com.cosmetic.scm.sales.SalesOrderRepository;
import com.cosmetic.scm.sales.SoAllocation;
import com.cosmetic.scm.sales.SoAllocationRepository;
import com.cosmetic.scm.salesreturn.SalesReturn;
import com.cosmetic.scm.salesreturn.SalesReturnLine;
import com.cosmetic.scm.salesreturn.SalesReturnLineRepository;
import com.cosmetic.scm.salesreturn.SalesReturnRepository;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.CurrentUser;
import com.cosmetic.scm.security.SignatureService;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 召回演练（模拟召回）（《化妆品生产质量管理规范》第六十三条；检查要点委托生产版第 24 项）。
 * 质量人员选一个已发货的成品批次发起：系统按出库、退货和库存记录列出在外数量的收货客户、委托生产的受托厂和有库存的仓库，
 * 逐一登记联系结果（客户可填反馈的在手数量，仓库填实盘数量）。最后一家联系完的时间减发起时间是用时，
 * 按 {@link RecallDrillRule} 判断是否达标；没达标要转 CAPA 才能提交，质量安全负责人输入密码审核，不能是发起人或提交人。
 * 只是演练：不冻结批次、不生成召回通知，也不改库存。写操作先锁演练记录（事务第一句）。
 * 超过 scm.recall.drill-interval-months 个月（默认 12）没有审核过的演练进总览待办。规则见 docs/PLM-默认规则.md 的“召回演练”。
 */
@Service
@RequiredArgsConstructor
public class RecallDrillService {

    private final RecallDrillRepository drills;
    private final RecallDrillLineRepository lines;
    private final BatchRepository batches;
    private final SoAllocationRepository allocations;
    private final SalesOrderRepository salesOrders;
    private final SalesReturnRepository returns;
    private final SalesReturnLineRepository returnLines;
    private final StockRepository stocks;
    private final StockTxnRepository txns;
    private final MfgOrderRepository mfgOrders;
    private final CapaRepository capas;
    private final DeviationService deviations;
    private final MasterLookup lookup;
    private final SignatureService signature;
    private final DocNoService docNo;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    @Value("${scm.recall.drill-interval-months:12}")
    private int intervalMonths;

    public record StartReq(@NotBlank(message = "请填写批号") @Size(max = 40, message = "批号最多 40 个字") String batchNo,
                           @NotBlank(message = "请写明模拟的召回情景") @Size(max = 500, message = "情景最多 500 个字") String scenario,
                           @NotNull(message = "请填写目标用时") @Min(value = 1, message = "目标用时至少 1 小时")
                           @Max(value = 720, message = "目标用时最多 720 小时") Integer targetHours) {
    }

    public record RecordReq(@NotNull(message = "请选择联系结果") DrillResult result,
                            @Qty @PositiveOrZero(message = "数量不能是负数") BigDecimal confirmedQty,
                            LocalDateTime contactedAt,
                            @Size(max = 300, message = "说明最多 300 个字") String note) {
    }

    public record SubmitReq(@NotBlank(message = "请填写演练总结") @Size(max = 1000, message = "演练总结最多 1000 个字") String summary) {
    }

    public record CapaReq(@NotNull(message = "请选择纠正还是预防") CapaType actionType,
                          @NotBlank(message = "请填写措施内容") @Size(max = 900, message = "措施内容最多 900 个字") String description,
                          @NotBlank(message = "请填写负责人") @Size(max = 64) String owner,
                          @NotNull(message = "请选择完成期限") LocalDate dueDate) {
    }

    public record ReviewReq(@Size(max = 300, message = "审核意见最多 300 个字") String note, @NotBlank(message = "请输入签批密码") String password) {
    }

    public record NoteReq(@NotBlank(message = "请写明原因") @Size(max = 300, message = "原因最多 300 个字") String note) {
    }

    public record LineView(Long id, int seq, DrillTarget target, String targetLabel, Long partnerId, Long warehouseId, String name, String contact,
                           BigDecimal expectedQty, DrillResult result, String resultLabel, BigDecimal confirmedQty, LocalDateTime contactedAt,
                           String recordedBy, String note) {
    }

    /** 演练记录。elapsed、located、met、gaps 按对象的登记结果实时算（提交后对象不能再改，和提交时存下的一致）。 */
    public record DrillView(Long id, String drillNo, Long batchId, String batchNo, String itemCode, String itemName, String unit, String scenario,
                            int targetHours, String balance, DrillStatus status, String statusLabel, String summary, List<LineView> lines,
                            int recordedCount, LocalDateTime finishedAt, Long elapsedMinutes, String elapsedHours, BigDecimal locatedPct, boolean met,
                            List<String> gaps, Long capaId, String capaNo, String capaStatus, String capaStatusLabel, String rejectNote, String reviewNote,
                            String cancelReason, String startedBy, LocalDateTime startedAt, String submittedBy, LocalDateTime submittedAt,
                            String reviewedBy, LocalDateTime reviewedAt) {
    }

    /** 发起前预览要联系的对象。 */
    public record PlanView(String batchNo, String itemName, String unit, String balance, List<LineView> lines) {
    }

    /** lastReviewed：最近一次审核过的演练的发起日期；due：该再演练了。 */
    public record Overview(List<DrillView> drills, int intervalMonths, LocalDate lastReviewed, boolean due, boolean hasShipments) {
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public Overview overview() {
        LocalDate last = lastReviewed();
        boolean shipped = allocations.count() > 0;
        return new Overview(views(drills.findTop300ByOrderByIdDesc()), intervalMonths, last,
                RecallDrillRule.due(shipped, last, time.today(), intervalMonths), shipped);
    }

    @Transactional(readOnly = true)
    public DrillView detail(Long id) {
        return views(List.of(drills.findById(id).orElseThrow(() -> BizException.notFound("召回演练")))).get(0);
    }

    /** 全部演练，导出用。 */
    @Transactional(readOnly = true)
    public List<DrillView> listAll() {
        return views(drills.findAllByOrderByIdDesc());
    }

    @Transactional(readOnly = true)
    public List<DrillView> pendingReview() {
        return views(drills.findByStatusInOrderById(List.of(DrillStatus.SUBMITTED)));
    }

    /** 总览待办“召回演练到期”：ref 是最近一次审核过的演练编号（没有为“—”）。 */
    public record Due(String ref, String detail) {
    }

    /** 该再演练了（总览待办）：有过出库，超过周期没有审核过的演练，而且没有进行中或待审核的演练。不到期返回空。 */
    @Transactional(readOnly = true)
    public java.util.Optional<Due> due() {
        RecallDrill last = drills.findFirstByStatusOrderByStartedAtDescIdDesc(DrillStatus.REVIEWED).orElse(null);
        LocalDate lastDate = last == null ? null : last.getStartedAt().toLocalDate();
        if (!RecallDrillRule.due(allocations.count() > 0, lastDate, time.today(), intervalMonths)
                || drills.existsByStatusIn(List.of(DrillStatus.IN_PROGRESS, DrillStatus.SUBMITTED))) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new Due(last == null ? "—" : last.getDrillNo(), last == null ? "还没有审核过的召回演练"
                : "最近一次演练 " + lastDate + " 发起，已超过 " + intervalMonths + " 个月"));
    }

    public int intervalMonths() {
        return intervalMonths;
    }

    @Transactional(readOnly = true)
    public PlanView preview(String batchNo) {
        Batch b = fgBatch(batchNo);
        Plan p = plan(b);
        Item item = lookup.item(b.getItemId());
        Map<Long, Partner> pm = lookup.partnerMap();
        Map<Long, Warehouse> wm = lookup.warehouseMap();
        List<LineView> ls = new ArrayList<>();
        for (RecallDrillLine l : p.lines()) {
            ls.add(lineView(l, pm, wm));
        }
        return new PlanView(b.getBatchNo(), item.getName(), item.getUnit(), p.balance(), ls);
    }

    private LocalDate lastReviewed() {
        return drills.findFirstByStatusOrderByStartedAtDescIdDesc(DrillStatus.REVIEWED).map(d -> d.getStartedAt().toLocalDate()).orElse(null);
    }

    // ---------- 写操作 ----------

    /** 发起演练：不改批次和库存，只记下此刻要联系的对象和数量平衡。 */
    @Transactional
    public DrillView start(StartReq req) {
        Batch b = fgBatch(req.batchNo());
        Plan p = plan(b);
        if (p.lines().stream().noneMatch(l -> l.getTarget() == DrillTarget.CUSTOMER)) {
            throw new BizException("批次 " + b.getBatchNo() + " 没有在外的数量（没有出库，或已全部退回），请选一个已发货的成品批次演练");
        }
        RecallDrill d = new RecallDrill();
        d.setDrillNo(docNo.next("RD", time.today()));
        d.setBatchId(b.getId());
        d.setScenario(req.scenario().trim());
        d.setTargetHours(req.targetHours());
        d.setBalance(p.balance());
        d.setStatus(DrillStatus.IN_PROGRESS);
        d.setStartedBy(currentUser.usernameOrSystem());
        d.setStartedAt(now());
        drills.save(d);
        int seq = 0;
        for (RecallDrillLine l : p.lines()) {
            l.setDrillId(d.getId());
            l.setSeq(++seq);
            lines.save(l);
        }
        Map<DrillTarget, Long> counts = p.lines().stream().collect(Collectors.groupingBy(RecallDrillLine::getTarget,
                () -> new EnumMap<>(DrillTarget.class), Collectors.counting()));
        Item item = lookup.item(b.getItemId());
        audit.log("发起召回演练", d.getDrillNo(), item.getName() + " " + b.getBatchNo() + "，" + counts.entrySet().stream()
                .map(e -> e.getKey().getLabel() + " " + e.getValue()).collect(Collectors.joining("、")) + "，目标用时 " + d.getTargetHours() + " 小时；"
                + p.balance(), d.getScenario());
        return views(List.of(d)).get(0);
    }

    /** 登记一个对象的联系结果，进行中可以重新登记（审计记修改前的）。联系时间不能早于发起、不能晚于现在。 */
    @Transactional
    public DrillView record(Long id, Long lineId, RecordReq req) {
        RecallDrill d = lockInProgress(id, "登记联系结果");
        RecallDrillLine l = lines.lockByIdAndDrillId(lineId, d.getId()).orElseThrow(() -> BizException.notFound("演练要联系的对象"));
        LocalDateTime now = now();
        LocalDateTime at = req.contactedAt() == null ? now : req.contactedAt().withNano(0);
        if (at.isAfter(now)) {
            throw new BizException("联系时间不能晚于现在");
        }
        if (at.isBefore(d.getStartedAt())) {
            throw new BizException("联系时间不能早于演练发起时间 " + d.getStartedAt().toString().replace('T', ' '));
        }
        String note = blankToNull(req.note());
        BigDecimal qty = req.confirmedQty();
        if (req.result() == DrillResult.UNREACHABLE) {
            if (note == null) {
                throw new BizException("联系不上请写明试过的方式（电话、微信、邮件等）");
            }
            qty = null;
        } else if (l.getTarget() == DrillTarget.WAREHOUSE && qty == null) {
            throw new BizException("仓库请填实盘数量");
        } else if (l.getTarget() == DrillTarget.FACTORY) {
            qty = null;
        }
        String before = l.getResult() == null ? null : "修改前：" + describe(l);
        l.setResult(req.result());
        l.setConfirmedQty(qty);
        l.setContactedAt(at);
        l.setRecordedBy(currentUser.usernameOrSystem());
        l.setNote(note);
        lines.save(l);
        audit.log("登记召回演练联系结果", d.getDrillNo(), describe(l), before);
        return views(List.of(d)).get(0);
    }

    /** 没达标转 CAPA，来源记“召回演练”。每次演练一个。 */
    @Transactional
    public DrillView createCapa(Long id, CapaReq req) {
        RecallDrill d = lockInProgress(id, "转 CAPA");
        if (d.getCapaId() != null) {
            throw new BizException("演练 " + d.getDrillNo() + " 已经转过 CAPA");
        }
        if (req.dueDate().isBefore(time.today())) {
            throw new BizException("完成期限不能早于今天");
        }
        Batch b = batches.findById(d.getBatchId()).orElseThrow();
        DeviationService.CapaView capa = deviations.createCapa(new DeviationService.CapaReq(CapaSource.RECALL_DRILL, d.getDrillNo(), null, req.actionType(),
                "召回演练（批号 " + b.getBatchNo() + "）没达标：" + req.description().trim(), req.owner(), req.dueDate()));
        d.setCapaId(capa.id());
        drills.save(d);
        audit.log("召回演练转 CAPA", d.getDrillNo(), "CAPA " + capa.capaNo());
        return views(List.of(d)).get(0);
    }

    /** 提交审核：每个对象都登记了结果；没达标的要先转 CAPA。存下用时、定位率和是否达标。 */
    @Transactional
    public DrillView submit(Long id, SubmitReq req) {
        RecallDrill d = lockInProgress(id, "提交审核");
        List<RecallDrillLine> ls = lines.lockByDrillId(d.getId());
        List<RecallDrillRule.Line> rule = ruleLines(ls, lookup.partnerMap(), lookup.warehouseMap());
        List<String> missing = RecallDrillRule.unrecorded(rule);
        if (!missing.isEmpty()) {
            throw new BizException(String.join("；", missing));
        }
        RecallDrillRule.Outcome o = RecallDrillRule.evaluate(d.getStartedAt(), d.getTargetHours(), rule);
        if (!o.met() && d.getCapaId() == null) {
            throw new BizException("演练没达标（" + String.join("；", o.gaps()) + "），请先转 CAPA 再提交");
        }
        d.setSummary(req.summary().trim());
        d.setFinishedAt(o.finishedAt());
        d.setElapsedMinutes(o.elapsedMinutes() == null ? null : o.elapsedMinutes().intValue());
        d.setLocatedPct(o.locatedPct());
        d.setMet(o.met());
        d.setStatus(DrillStatus.SUBMITTED);
        d.setSubmittedBy(currentUser.usernameOrSystem());
        d.setSubmittedAt(now());
        d.setRejectNote(null);
        drills.save(d);
        audit.log("提交召回演练", d.getDrillNo(), (o.met() ? "达标" : "没达标") + "，用时 " + RecallDrillRule.hours(o.elapsedMinutes()) + " 小时（目标 "
                + d.getTargetHours() + " 小时）" + (o.locatedPct() == null ? "" : "，在外数量定位 " + o.locatedPct().stripTrailingZeros().toPlainString() + "%"),
                d.getSummary());
        return views(List.of(d)).get(0);
    }

    /** 质量安全负责人审核：输入密码，不能是发起人或提交人。 */
    @Transactional
    public DrillView review(Long id, ReviewReq req) {
        RecallDrill d = lock(id);
        require(d, DrillStatus.SUBMITTED, "审核");
        AppUser signer = signature.verify(req.password());
        if (signer.getUsername().equals(d.getStartedBy()) || signer.getUsername().equals(d.getSubmittedBy())) {
            throw new BizException("审核人不能是演练的发起人或提交人");
        }
        d.setStatus(DrillStatus.REVIEWED);
        d.setReviewedBy(signer.getUsername());
        d.setReviewedAt(now());
        d.setReviewNote(blankToNull(req.note()));
        drills.save(d);
        audit.log("审核召回演练", d.getDrillNo(), Boolean.TRUE.equals(d.getMet()) ? "达标" : "没达标", d.getReviewNote());
        return views(List.of(d)).get(0);
    }

    /** 退回：回到进行中，补登记后重新提交。转出的 CAPA 保留。 */
    @Transactional
    public DrillView reject(Long id, NoteReq req) {
        RecallDrill d = lock(id);
        require(d, DrillStatus.SUBMITTED, "退回");
        d.setStatus(DrillStatus.IN_PROGRESS);
        d.setRejectNote(req.note().trim());
        d.setFinishedAt(null);
        d.setElapsedMinutes(null);
        d.setLocatedPct(null);
        d.setMet(null);
        drills.save(d);
        audit.log("退回召回演练", d.getDrillNo(), "退回补充", d.getRejectNote());
        return views(List.of(d)).get(0);
    }

    /** 作废：进行中和待审核的可以作废，登记的结果和转出的 CAPA 保留。 */
    @Transactional
    public DrillView cancel(Long id, NoteReq req) {
        RecallDrill d = lock(id);
        if (d.getStatus() != DrillStatus.IN_PROGRESS && d.getStatus() != DrillStatus.SUBMITTED) {
            throw new BizException("演练 " + d.getDrillNo() + " 当前为" + d.getStatus().getLabel() + "，不能作废");
        }
        d.setStatus(DrillStatus.CANCELLED);
        d.setCancelReason(req.note().trim());
        drills.save(d);
        audit.log("作废召回演练", d.getDrillNo(), "登记的结果和 CAPA 保留", d.getCancelReason());
        return views(List.of(d)).get(0);
    }

    // ---------- 内部 ----------

    private record Plan(List<RecallDrillLine> lines, String balance) {
    }

    private Batch fgBatch(String batchNo) {
        String no = batchNo == null ? "" : batchNo.trim();
        Batch b = batches.findByBatchNo(no).orElseThrow(() -> BizException.notFound("批号 " + no + " "));
        Item item = lookup.item(b.getItemId());
        if (item.getItemType() != ItemType.FG) {
            throw new BizException("召回演练针对成品批次，" + item.getName() + " 不是成品");
        }
        return b;
    }

    /**
     * 要联系的对象：在外数量大于 0 的收货客户（发出减退回，按首次出库先后）、委托生产这批的受托厂、有库存的仓库；
     * 以及按库存流水类型汇总的数量平衡。
     */
    private Plan plan(Batch b) {
        Map<Long, Partner> pm = lookup.partnerMap();
        Map<Long, Warehouse> wm = lookup.warehouseMap();
        List<SoAllocation> allocs = allocations.findByBatchIdIn(List.of(b.getId()));
        Map<Long, SalesOrder> sos = salesOrders.findAllById(allocs.stream().map(SoAllocation::getSoId).distinct().toList()).stream()
                .collect(Collectors.toMap(SalesOrder::getId, Function.identity()));
        Map<Long, BigDecimal> out = new LinkedHashMap<>();
        allocs.stream().sorted(Comparator.comparing(SoAllocation::getId))
                .forEach(a -> out.merge(sos.get(a.getSoId()).getCustomerId(), a.getQty(), BigDecimal::add));
        List<SalesReturnLine> rls = returnLines.findByBatchIdIn(List.of(b.getId()));
        if (!rls.isEmpty()) {
            Map<Long, SalesReturn> rm = returns.findAllById(rls.stream().map(SalesReturnLine::getReturnId).distinct().toList()).stream()
                    .collect(Collectors.toMap(SalesReturn::getId, Function.identity()));
            rls.forEach(l -> out.computeIfPresent(rm.get(l.getReturnId()).getCustomerId(), (k, v) -> v.subtract(l.getQty())));
        }
        List<RecallDrillLine> ls = new ArrayList<>();
        out.forEach((customerId, qty) -> {
            if (qty.signum() > 0) {
                ls.add(newLine(DrillTarget.CUSTOMER, customerId, null, qty, pm.get(customerId)));
            }
        });
        if (b.getSourceType() == SourceType.MO && b.getSourceNo() != null) {
            MfgOrder mo = mfgOrders.findByMoNo(b.getSourceNo()).orElse(null);
            if (mo != null && mo.getMoType() == MoType.OEM && mo.getFactoryId() != null) {
                ls.add(newLine(DrillTarget.FACTORY, mo.getFactoryId(), null, null, pm.get(mo.getFactoryId())));
            }
        }
        Map<Long, BigDecimal> byWh = new java.util.TreeMap<>();
        for (Stock s : stocks.findByBatchId(b.getId())) {
            if (s.getQty().signum() > 0) {
                byWh.merge(s.getWarehouseId(), s.getQty(), BigDecimal::add);
            }
        }
        byWh.forEach((whId, qty) -> {
            RecallDrillLine l = newLine(DrillTarget.WAREHOUSE, null, whId, qty, null);
            ls.add(l);
        });
        return new Plan(ls, balance(b, byWh.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)));
    }

    private static RecallDrillLine newLine(DrillTarget target, Long partnerId, Long warehouseId, BigDecimal qty, Partner p) {
        RecallDrillLine l = new RecallDrillLine();
        l.setTarget(target);
        l.setPartnerId(partnerId);
        l.setWarehouseId(warehouseId);
        l.setExpectedQty(qty);
        String contact = p == null ? null : blankToNull(p.getContact());
        l.setContact(contact == null ? null : contact.length() > 200 ? contact.substring(0, 200) : contact);
        return l;
    }

    /** 按库存流水类型汇总（调拨不计），和在库合计对照。 */
    private String balance(Batch b, BigDecimal onHand) {
        Map<TxnType, BigDecimal> byType = new EnumMap<>(TxnType.class);
        for (StockTxn t : txns.findByBatchIdOrderByIdDesc(b.getId())) {
            if (t.getTxnType() != TxnType.TRANSFER) {
                byType.merge(t.getTxnType(), t.getQtyDelta(), BigDecimal::add);
            }
        }
        BigDecimal sum = byType.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        String parts = byType.entrySet().stream().filter(e -> e.getValue().signum() != 0)
                .map(e -> e.getKey().getLabel() + " " + (e.getValue().signum() > 0 ? "+" : "") + plain(e.getValue())).collect(Collectors.joining("；"));
        String s = (parts.isEmpty() ? "没有库存流水" : parts) + "；在库合计 " + plain(onHand)
                + (sum.compareTo(onHand) == 0 ? "" : "（流水合计 " + plain(sum) + "，和在库不一致，请核对）");
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }

    private static List<RecallDrillRule.Line> ruleLines(List<RecallDrillLine> ls, Map<Long, Partner> pm, Map<Long, Warehouse> wm) {
        return ls.stream().map(l -> new RecallDrillRule.Line(l.getSeq(), l.getTarget(), nameOf(l, pm, wm), l.getExpectedQty(), l.getResult(),
                l.getConfirmedQty(), l.getContactedAt())).toList();
    }

    private static String nameOf(RecallDrillLine l, Map<Long, Partner> pm, Map<Long, Warehouse> wm) {
        if (l.getWarehouseId() != null) {
            Warehouse w = wm.get(l.getWarehouseId());
            return w == null ? String.valueOf(l.getWarehouseId()) : w.getName();
        }
        Partner p = l.getPartnerId() == null ? null : pm.get(l.getPartnerId());
        return p == null ? String.valueOf(l.getPartnerId()) : p.getName();
    }

    private String describe(RecallDrillLine l) {
        String name = nameOf(l, lookup.partnerMap(), lookup.warehouseMap());
        return l.getTarget().getLabel() + " " + name + "：" + l.getResult().getLabel()
                + (l.getConfirmedQty() == null ? "" : (l.getTarget() == DrillTarget.WAREHOUSE ? "，实盘 " : "，反馈在手 ") + plain(l.getConfirmedQty()))
                + "，" + l.getContactedAt().toString().replace('T', ' ') + (l.getNote() == null ? "" : "，" + l.getNote());
    }

    private LocalDateTime now() {
        // MySQL DATETIME 会把小数秒四舍五入，统一截到整秒，用时和“不能早于发起”的判断两边一致
        return time.now().withNano(0);
    }

    private RecallDrill lock(Long id) {
        return drills.lockById(id).orElseThrow(() -> BizException.notFound("召回演练"));
    }

    private RecallDrill lockInProgress(Long id, String action) {
        RecallDrill d = lock(id);
        require(d, DrillStatus.IN_PROGRESS, action);
        return d;
    }

    private static void require(RecallDrill d, DrillStatus need, String action) {
        if (d.getStatus() != need) {
            throw new BizException("演练 " + d.getDrillNo() + " 当前为" + d.getStatus().getLabel() + "，不能" + action);
        }
    }

    private static LineView lineView(RecallDrillLine l, Map<Long, Partner> pm, Map<Long, Warehouse> wm) {
        return new LineView(l.getId(), l.getSeq() == null ? 0 : l.getSeq(), l.getTarget(), l.getTarget().getLabel(), l.getPartnerId(), l.getWarehouseId(),
                nameOf(l, pm, wm), l.getContact(), l.getExpectedQty(), l.getResult(), l.getResult() == null ? null : l.getResult().getLabel(),
                l.getConfirmedQty(), l.getContactedAt(), l.getRecordedBy(), l.getNote());
    }

    /** 一次查完对象、批次、CAPA，不在循环里查库。 */
    private List<DrillView> views(List<RecallDrill> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, List<RecallDrillLine>> byDrill = lines.findByDrillIdInOrderByDrillIdAscSeqAsc(list.stream().map(RecallDrill::getId).toList()).stream()
                .collect(Collectors.groupingBy(RecallDrillLine::getDrillId));
        Map<Long, Batch> bm = batches.findAllById(list.stream().map(RecallDrill::getBatchId).distinct().toList()).stream()
                .collect(Collectors.toMap(Batch::getId, Function.identity()));
        Map<Long, Capa> cm = new HashMap<>();
        capas.findAllById(list.stream().map(RecallDrill::getCapaId).filter(Objects::nonNull).distinct().toList()).forEach(c -> cm.put(c.getId(), c));
        Map<Long, Item> im = lookup.itemMap();
        Map<Long, Partner> pm = lookup.partnerMap();
        Map<Long, Warehouse> wm = lookup.warehouseMap();
        List<DrillView> out = new ArrayList<>();
        for (RecallDrill d : list) {
            List<RecallDrillLine> ls = byDrill.getOrDefault(d.getId(), List.of());
            RecallDrillRule.Outcome o = RecallDrillRule.evaluate(d.getStartedAt(), d.getTargetHours(), ruleLines(ls, pm, wm));
            Batch b = bm.get(d.getBatchId());
            Item i = b == null ? null : im.get(b.getItemId());
            Capa c = d.getCapaId() == null ? null : cm.get(d.getCapaId());
            out.add(new DrillView(d.getId(), d.getDrillNo(), d.getBatchId(), b == null ? null : b.getBatchNo(), i == null ? null : i.getCode(),
                    i == null ? null : i.getName(), i == null ? null : i.getUnit(), d.getScenario(), d.getTargetHours(), d.getBalance(), d.getStatus(),
                    d.getStatus().getLabel(), d.getSummary(), ls.stream().map(l -> lineView(l, pm, wm)).toList(),
                    (int) ls.stream().filter(l -> l.getResult() != null).count(), o.finishedAt(), o.elapsedMinutes(),
                    o.elapsedMinutes() == null ? null : RecallDrillRule.hours(o.elapsedMinutes()), o.locatedPct(), o.met(), o.gaps(), d.getCapaId(),
                    c == null ? null : c.getCapaNo(), c == null ? null : c.getStatus().name(), c == null ? null : c.getStatus().getLabel(),
                    d.getRejectNote(), d.getReviewNote(), d.getCancelReason(), d.getStartedBy(), d.getStartedAt(), d.getSubmittedBy(), d.getSubmittedAt(),
                    d.getReviewedBy(), d.getReviewedAt()));
        }
        return out;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
