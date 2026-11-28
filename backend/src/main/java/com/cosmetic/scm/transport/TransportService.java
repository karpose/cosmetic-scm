package com.cosmetic.scm.transport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.DocNoService;
import com.cosmetic.scm.common.FieldDiff;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.CaseStatus;
import com.cosmetic.scm.domain.DeviationLevel;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.MoStatus;
import com.cosmetic.scm.domain.MoType;
import com.cosmetic.scm.domain.TransportDirection;
import com.cosmetic.scm.domain.TransportDocType;
import com.cosmetic.scm.domain.TransportMode;
import com.cosmetic.scm.domain.rules.TransportRule;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.Warehouse;
import com.cosmetic.scm.production.MfgOrder;
import com.cosmetic.scm.production.MfgOrderRepository;
import com.cosmetic.scm.qms.Deviation;
import com.cosmetic.scm.qms.DeviationRepository;
import com.cosmetic.scm.qms.DeviationService;
import com.cosmetic.scm.sales.SalesOrder;
import com.cosmetic.scm.sales.SalesOrderRepository;
import com.cosmetic.scm.sales.SoAllocation;
import com.cosmetic.scm.sales.SoAllocationRepository;
import com.cosmetic.scm.sales.SoLine;
import com.cosmetic.scm.sales.SoLineRepository;
import com.cosmetic.scm.security.CurrentUser;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * 产品运输条件和运输记录（《化妆品生产质量管理规范》第五十九条；检查要点委托生产版序号 20 第 2 点“产品的贮存、运输条件是否符合……产品标签标示的要求”）。
 * 质量部门给成品设运输条件（不内置）；销售发运按出库单、受托厂成品到仓按委托单登记运输记录，一条记录可包含多张单据（同一车、同一批快递揽收）。
 * 条件取这些产品里最严的，判定在 {@link TransportRule}；不符合的自动生成一份偏差（只涉及一个批次时挂在批次上）。
 * 记录只增不改，录错的由质量部门作废（写明原因）后重登。登记先锁这些单据行（按 id 顺序，事务第一句），作废先锁记录。
 * 设了条件的成品出库或完工后超过 scm.transport.record-grace-days（默认 3）天还没登记的进总览待办。规则见 docs/PLM-默认规则.md 的“运输”。
 */
@Service
@RequiredArgsConstructor
public class TransportService {

    public static final int LIST_CAP = 500;

    /** 一条记录最多包含的单据数（一批快递揽收的电商订单）。 */
    public static final int MAX_DOCS = 500;

    /** 登记到仓运输时，可选最近多少天完工的委托单。 */
    public static final int INBOUND_LOOKBACK_DAYS = 120;

    private static final int FUTURE_TOLERANCE_MINUTES = 5;

    private static final DateTimeFormatter MIN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final TransportReqRepository reqs;
    private final TransportRecordRepository records;
    private final TransportDocRepository docs;
    private final SalesOrderRepository salesOrders;
    private final SoLineRepository soLines;
    private final SoAllocationRepository allocations;
    private final MfgOrderRepository mfgOrders;
    private final BatchRepository batches;
    private final DeviationRepository deviationRepo;
    private final DeviationService deviationService;
    private final MasterLookup lookup;
    private final DocNoService docNo;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    @Value("${scm.transport.record-grace-days:3}")
    private int graceDays;

    @Value("${scm.transport.deviation-level:MINOR}")
    private DeviationLevel deviationLevel;

    // ---------- 请求和视图 ----------

    public record ReqReq(@NotNull(message = "请选择成品") Long itemId,
                         @DecimalMin(value = "-80", message = "温度下限不能低于 -80℃") @DecimalMax(value = "100", message = "温度下限不能高于 100℃")
                         @Digits(integer = 3, fraction = 1, message = "温度最多 1 位小数") BigDecimal tempMin,
                         @DecimalMin(value = "-80", message = "温度上限不能低于 -80℃") @DecimalMax(value = "100", message = "温度上限不能高于 100℃")
                         @Digits(integer = 3, fraction = 1, message = "温度最多 1 位小数") BigDecimal tempMax,
                         boolean monitorRequired,
                         @Size(max = 300, message = "运输注意事项最多 300 个字") String handlingNote,
                         @NotNull(message = "请选择从哪天起要求登记") LocalDate effectiveFrom,
                         Boolean active) {
    }

    public record ReqView(Long id, Long itemId, String itemCode, String itemName, BigDecimal tempMin, BigDecimal tempMax, boolean monitorRequired,
                          String handlingNote, String text, LocalDate effectiveFrom, boolean active, String createdBy, LocalDateTime createdAt,
                          String updatedBy, LocalDateTime updatedAt) {
    }

    public record CreateReq(@NotNull(message = "请选择运输方向") TransportDirection direction,
                            @NotEmpty(message = "请至少选一张单据") @Size(max = MAX_DOCS, message = "一条运输记录最多 500 张单据，请分开登记") List<Long> docIds,
                            @NotNull(message = "请选择运输方式") TransportMode mode,
                            @Size(max = 100, message = "承运单位最多 100 个字") String carrier,
                            @Size(max = 100, message = "运单号最多 100 个字") String waybillNo,
                            @NotNull(message = "请选择发运时间") LocalDateTime shippedAt,
                            LocalDateTime deliveredAt,
                            boolean tempMonitored,
                            @DecimalMin(value = "-80", message = "温度不能低于 -80℃") @DecimalMax(value = "100", message = "温度不能高于 100℃")
                            @Digits(integer = 3, fraction = 1, message = "温度最多 1 位小数") BigDecimal tempLow,
                            @DecimalMin(value = "-80", message = "温度不能低于 -80℃") @DecimalMax(value = "100", message = "温度不能高于 100℃")
                            @Digits(integer = 3, fraction = 1, message = "温度最多 1 位小数") BigDecimal tempHigh,
                            @Size(max = 60, message = "记录仪编号最多 60 个字") String device,
                            boolean damaged,
                            @Size(max = 300, message = "异常情况最多 300 个字") String damageNote,
                            @Size(max = 300, message = "备注最多 300 个字") String note) {
    }

    public record VoidReq(@jakarta.validation.constraints.NotBlank(message = "请写明作废原因") @Size(max = 300, message = "作废原因最多 300 个字") String reason) {
    }

    public record Option(String code, String label) {
    }

    /** 登记时可选的单据。required：有设了运输条件的成品；reqText：这张单据的条件。 */
    public record Candidate(TransportDocType docType, Long docId, String docNo, LocalDate date, String partyName, String warehouseName, String itemsText,
                            String batchesText, boolean required, String reqText) {
    }

    public record DocView(TransportDocType docType, String docTypeLabel, Long docId, String docNo, LocalDate date, String partyName, String warehouseName,
                          String itemsText, String batchesText) {
    }

    public record RecordView(Long id, String recordNo, TransportDirection direction, String directionLabel, TransportMode mode, String modeLabel,
                             String carrier, String waybillNo, LocalDateTime shippedAt, LocalDateTime deliveredAt, String reqText, boolean reqMonitor,
                             boolean tempMonitored, BigDecimal tempLow, BigDecimal tempHigh, String readingText, String device, boolean damaged,
                             String damageNote, boolean conforming, String problem, Long deviationId, String deviationNo, CaseStatus deviationStatus,
                             String deviationStatusLabel, String note, boolean voided, String voidReason, String voidedBy, LocalDateTime voidedAt,
                             String createdBy, LocalDateTime createdAt, List<DocView> docs, String docsText) {
    }

    public record RecordList(List<RecordView> rows, long total, int cap, LocalDate from, LocalDate to) {
    }

    /**
     * 要登记、还没登记的单据。出库单按仓库和日期合成一行（count 张），委托单一张一行；overdue：超过登记期限（进总览待办）。
     */
    public record MissingRow(TransportDirection direction, String directionLabel, LocalDate date, Long warehouseId, String place, long count,
                             Long docId, String docNo, boolean overdue, String detail) {
    }

    public record Missing(List<MissingRow> rows, int graceDays, long activeReqs) {
    }

    public record TodoRow(String kind, String title, String ref, String detail) {
    }

    // ---------- 选项 ----------

    public List<Option> modes() {
        return java.util.Arrays.stream(TransportMode.values()).map(m -> new Option(m.name(), m.getLabel())).toList();
    }

    // ---------- 运输条件 ----------

    @Transactional(readOnly = true)
    public List<ReqView> requirements() {
        Map<Long, Item> items = lookup.itemMap();
        return reqs.findAllByOrderByIdAsc().stream().map(q -> reqView(q, items.get(q.getItemId())))
                .sorted(Comparator.comparing(ReqView::itemCode, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
    }

    @Transactional
    public ReqView createRequirement(ReqReq req) {
        Item item = requireFg(req.itemId());
        if (reqs.existsByItemId(item.getId())) {
            throw new BizException(item.getName() + " 已经设了运输条件，请在列表里修改");
        }
        TransportReq q = new TransportReq();
        q.setItemId(item.getId());
        q.setActive(true);
        q.setCreatedBy(currentUser.usernameOrSystem());
        q.setCreatedAt(time.now());
        apply(q, req);
        reqs.saveAndFlush(q);
        audit.log("设定运输条件", item.getCode(), item.getName() + "：" + textOf(q) + "，" + q.getEffectiveFrom() + " 起出库和到仓要登记运输记录");
        return reqView(q, item);
    }

    @Transactional
    public ReqView updateRequirement(Long id, ReqReq req) {
        TransportReq q = reqs.lockById(id).orElseThrow(() -> BizException.notFound("运输条件"));
        if (!Objects.equals(q.getItemId(), req.itemId())) {
            throw new BizException("运输条件不能换成品；要给别的成品设条件请新增");
        }
        Item item = lookup.item(q.getItemId());
        Map<String, Object> before = fields(q);
        if (req.active() != null) {
            q.setActive(req.active());
        }
        apply(q, req);
        q.setUpdatedBy(currentUser.usernameOrSystem());
        q.setUpdatedAt(time.now());
        reqs.save(q);
        String diff = FieldDiff.of(before, fields(q));
        audit.log("修改运输条件", item == null ? String.valueOf(q.getItemId()) : item.getCode(), diff.isEmpty() ? "没有变化" : diff);
        return reqView(q, item);
    }

    private Item requireFg(Long itemId) {
        Item item = lookup.item(itemId);
        if (item == null) {
            throw BizException.notFound("物料");
        }
        if (item.getItemType() != ItemType.FG) {
            throw new BizException(item.getName() + " 不是成品；运输条件只给成品设");
        }
        return item;
    }

    private void apply(TransportReq q, ReqReq req) {
        String bad = TransportRule.checkRequirement(req.tempMin(), req.tempMax(), req.monitorRequired(), req.handlingNote());
        if (bad != null) {
            throw new BizException(bad);
        }
        q.setTempMin(req.tempMin());
        q.setTempMax(req.tempMax());
        q.setMonitorRequired(req.monitorRequired());
        q.setHandlingNote(blankToNull(req.handlingNote()));
        q.setEffectiveFrom(req.effectiveFrom());
    }

    private static String textOf(TransportReq q) {
        return TransportRule.text(q.getTempMin(), q.getTempMax(), q.isMonitorRequired(), q.getHandlingNote());
    }

    private Map<String, Object> fields(TransportReq q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("温度下限", q.getTempMin());
        m.put("温度上限", q.getTempMax());
        m.put("记录途中温度", q.isMonitorRequired());
        m.put("注意事项", q.getHandlingNote());
        m.put("生效日期", q.getEffectiveFrom());
        m.put("启用", q.isActive());
        return m;
    }

    private static ReqView reqView(TransportReq q, Item item) {
        return new ReqView(q.getId(), q.getItemId(), item == null ? null : item.getCode(), item == null ? null : item.getName(), q.getTempMin(), q.getTempMax(),
                q.isMonitorRequired(), q.getHandlingNote(), textOf(q), q.getEffectiveFrom(), q.isActive(), q.getCreatedBy(), q.getCreatedAt(),
                q.getUpdatedBy(), q.getUpdatedAt());
    }

    // ---------- 登记时可选的单据 ----------

    /** OUTBOUND：这天（这个仓库）出库、还没有有效运输记录的出库单；INBOUND：最近 120 天完工入库、还没有运输记录的委托单。 */
    @Transactional(readOnly = true)
    public List<Candidate> candidates(TransportDirection direction, LocalDate date, Long warehouseId) {
        if (direction == TransportDirection.OUTBOUND) {
            LocalDate d = date == null ? time.today() : date;
            List<SalesOrder> list = docs.unrecordedSos(d, warehouseId, TransportDocType.SO, PageRequest.of(0, MAX_DOCS));
            return soCandidates(list, names());
        }
        LocalDateTime now = time.now();
        List<MfgOrder> list = docs.unrecordedOemOrders(now.minusDays(INBOUND_LOOKBACK_DAYS), now.plusDays(1), false, MoType.OEM, MoStatus.COMPLETED, TransportDocType.MO);
        return moCandidates(list, names());
    }

    private List<Candidate> soCandidates(List<SalesOrder> list, Names n) {
        if (list.isEmpty()) {
            return List.of();
        }
        DocInfo info = soInfo(list, n);
        return list.stream().map(s -> {
            List<TransportRule.Requirement> r = info.reqs().getOrDefault(s.getId(), List.of());
            return new Candidate(TransportDocType.SO, s.getId(), s.getSoNo(), s.getOrderDate(), partyName(n, s.getCustomerId()), warehouseName(n, s.getWarehouseId()),
                    info.items().get(s.getId()), info.batches().get(s.getId()), !r.isEmpty(), r.isEmpty() ? null : TransportRule.combine(r).text());
        }).toList();
    }

    private List<Candidate> moCandidates(List<MfgOrder> list, Names n) {
        if (list.isEmpty()) {
            return List.of();
        }
        DocInfo info = moInfo(list, n);
        return list.stream().map(m -> {
            List<TransportRule.Requirement> r = info.reqs().getOrDefault(m.getId(), List.of());
            return new Candidate(TransportDocType.MO, m.getId(), m.getMoNo(), m.getCompletedAt().toLocalDate(), partyName(n, m.getFactoryId()), null,
                    info.items().get(m.getId()), info.batches().get(m.getId()), !r.isEmpty(), r.isEmpty() ? null : TransportRule.combine(r).text());
        }).toList();
    }

    /** 单据的产品、批次文字，以及在这张单据日期生效的运输条件。 */
    private record DocInfo(Map<Long, String> items, Map<Long, String> batches, Map<Long, List<TransportRule.Requirement>> reqs, Map<Long, Set<Long>> batchIds) {
    }

    private DocInfo soInfo(List<SalesOrder> list, Names n) {
        List<Long> ids = list.stream().map(SalesOrder::getId).toList();
        Map<Long, LocalDate> dates = list.stream().collect(Collectors.toMap(SalesOrder::getId, SalesOrder::getOrderDate));
        Map<Long, List<SoLine>> lines = soLines.findBySoIdIn(ids).stream().collect(Collectors.groupingBy(SoLine::getSoId));
        Map<Long, List<SoAllocation>> allocs = allocations.findBySoIdIn(ids).stream().collect(Collectors.groupingBy(SoAllocation::getSoId));
        Map<Long, Item> items = n.items();
        Set<Long> itemIds = lines.values().stream().flatMap(List::stream).map(SoLine::getItemId).collect(Collectors.toSet());
        Map<Long, TransportReq> active = activeReqs(itemIds);
        Map<Long, Batch> bs = batchMap(allocs.values().stream().flatMap(List::stream).map(SoAllocation::getBatchId).collect(Collectors.toSet()));
        Map<Long, String> itemText = new HashMap<>();
        Map<Long, String> batchText = new HashMap<>();
        Map<Long, List<TransportRule.Requirement>> r = new HashMap<>();
        Map<Long, Set<Long>> batchIds = new HashMap<>();
        for (Long id : ids) {
            List<SoLine> ls = lines.getOrDefault(id, List.of());
            itemText.put(id, ls.stream().map(l -> name(items, l.getItemId()) + " ×" + plain(l.getQty())).collect(Collectors.joining("、")));
            Set<Long> b = allocs.getOrDefault(id, List.of()).stream().map(SoAllocation::getBatchId).collect(Collectors.toCollection(LinkedHashSet::new));
            batchIds.put(id, b);
            batchText.put(id, b.stream().map(bs::get).filter(Objects::nonNull).map(Batch::getBatchNo).collect(Collectors.joining("、")));
            r.put(id, requirementsOf(ls.stream().map(SoLine::getItemId).distinct().toList(), active, items, dates.get(id)));
        }
        return new DocInfo(itemText, batchText, r, batchIds);
    }

    private DocInfo moInfo(List<MfgOrder> list, Names n) {
        Map<Long, Item> items = n.items();
        Map<Long, TransportReq> active = activeReqs(list.stream().map(MfgOrder::getFgItemId).collect(Collectors.toSet()));
        Map<Long, Batch> bs = batchMap(list.stream().map(MfgOrder::getOutBatchId).filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<Long, String> itemText = new HashMap<>();
        Map<Long, String> batchText = new HashMap<>();
        Map<Long, List<TransportRule.Requirement>> r = new HashMap<>();
        Map<Long, Set<Long>> batchIds = new HashMap<>();
        for (MfgOrder m : list) {
            itemText.put(m.getId(), name(items, m.getFgItemId()) + (m.getActualQty() == null ? "" : " ×" + plain(m.getActualQty())));
            Batch b = m.getOutBatchId() == null ? null : bs.get(m.getOutBatchId());
            batchText.put(m.getId(), b == null ? "" : b.getBatchNo());
            batchIds.put(m.getId(), m.getOutBatchId() == null ? Set.of() : Set.of(m.getOutBatchId()));
            LocalDate d = m.getCompletedAt() == null ? time.today() : m.getCompletedAt().toLocalDate();
            r.put(m.getId(), requirementsOf(List.of(m.getFgItemId()), active, items, d));
        }
        return new DocInfo(itemText, batchText, r, batchIds);
    }

    private Map<Long, TransportReq> activeReqs(Collection<Long> itemIds) {
        return itemIds.isEmpty() ? Map.of()
                : reqs.findByItemIdInAndActiveTrue(itemIds).stream().collect(Collectors.toMap(TransportReq::getItemId, Function.identity()));
    }

    private static List<TransportRule.Requirement> requirementsOf(List<Long> itemIds, Map<Long, TransportReq> active, Map<Long, Item> items, LocalDate date) {
        List<TransportRule.Requirement> out = new ArrayList<>();
        for (Long id : itemIds) {
            TransportReq q = active.get(id);
            if (q != null && !q.getEffectiveFrom().isAfter(date)) {
                out.add(new TransportRule.Requirement(name(items, id), q.getTempMin(), q.getTempMax(), q.isMonitorRequired(), q.getHandlingNote()));
            }
        }
        return out;
    }

    private Map<Long, Batch> batchMap(Set<Long> ids) {
        return ids.isEmpty() ? Map.of() : batches.findAllById(ids).stream().collect(Collectors.toMap(Batch::getId, Function.identity()));
    }

    // ---------- 登记 ----------

    @Transactional
    public RecordView create(CreateReq req) {
        boolean outbound = req.direction() == TransportDirection.OUTBOUND;
        TransportDocType type = outbound ? TransportDocType.SO : TransportDocType.MO;
        List<Long> ids = req.docIds().stream().filter(Objects::nonNull).distinct().sorted().toList();
        if (ids.isEmpty()) {
            throw new BizException("请至少选一张单据");
        }
        // 先按 id 顺序锁住这些单据（事务第一句），再查它们是不是已经在别的运输记录里
        List<SalesOrder> sos = new ArrayList<>();
        List<MfgOrder> mos = new ArrayList<>();
        for (Long id : ids) {
            if (outbound) {
                sos.add(salesOrders.lockById(id).orElseThrow(() -> BizException.notFound("出库单")));
            } else {
                mos.add(mfgOrders.lockById(id).orElseThrow(() -> BizException.notFound("委托单")));
            }
        }
        Map<Long, String> docNos = new LinkedHashMap<>();
        sos.forEach(s -> docNos.put(s.getId(), s.getSoNo()));
        mos.forEach(m -> docNos.put(m.getId(), m.getMoNo()));
        if (!outbound) {
            if (req.mode() == TransportMode.PICKUP) {
                throw new BizException("客户自提只用于销售发运；受托厂送货请选专车、物流、快递或冷链");
            }
            for (MfgOrder m : mos) {
                if (m.getMoType() != MoType.OEM) {
                    throw new BizException(m.getMoNo() + " 是自产生产单；到仓运输只登记委托生产的成品");
                }
                if (m.getStatus() != MoStatus.COMPLETED) {
                    throw new BizException(m.getMoNo() + " 还没有完工入库，完工入库后再登记到仓运输");
                }
            }
        }
        List<TransportDoc> taken = docs.findByDocTypeAndDocIdInAndDupKeyIsNotNull(type, ids);
        if (!taken.isEmpty()) {
            Map<Long, String> recNos = records.findAllById(taken.stream().map(TransportDoc::getRecordId).distinct().toList()).stream()
                    .collect(Collectors.toMap(TransportRecord::getId, TransportRecord::getRecordNo));
            throw new BizException(taken.stream().map(d -> docNos.get(d.getDocId()) + " 已在运输记录 " + recNos.get(d.getRecordId()) + " 里")
                    .collect(Collectors.joining("；")) + "。录错了请让质量部门作废那条记录后重新登记");
        }
        String carrier = blankToNull(req.carrier());
        if (carrier == null && req.mode() != TransportMode.PICKUP) {
            throw new BizException("请填写承运单位（客户自提可以不填）");
        }
        LocalDateTime latest = time.now().plusMinutes(FUTURE_TOLERANCE_MINUTES);
        LocalDateTime shipped = req.shippedAt().truncatedTo(ChronoUnit.MINUTES);
        LocalDateTime delivered = req.deliveredAt() == null ? null : req.deliveredAt().truncatedTo(ChronoUnit.MINUTES);
        if (shipped.isAfter(latest)) {
            throw new BizException("发运时间不能晚于现在");
        }
        if (delivered != null && (delivered.isBefore(shipped) || delivered.isAfter(latest))) {
            throw new BizException(delivered.isBefore(shipped) ? "到达时间不能早于发运时间" : "到达时间不能晚于现在");
        }
        for (SalesOrder s : sos) {
            if (shipped.toLocalDate().isBefore(s.getOrderDate())) {
                throw new BizException(s.getSoNo() + " 的出库日期是 " + s.getOrderDate() + "，发运时间不能早于出库日期");
            }
        }
        String bad = TransportRule.checkReading(req.tempMonitored(), req.tempLow(), req.tempHigh());
        if (bad != null) {
            throw new BizException(bad);
        }
        if (!req.damaged() && blankToNull(req.damageNote()) != null) {
            throw new BizException("没有勾选“到货异常”时，请不要填写异常情况");
        }
        if (req.damaged() && blankToNull(req.damageNote()) == null) {
            throw new BizException("请写明到货异常的情况（破损、受潮、污染等）");
        }

        Names n = names();
        DocInfo info = outbound ? soInfo(sos, n) : moInfo(mos, n);
        List<TransportRule.Requirement> all = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (Long id : ids) {
            for (TransportRule.Requirement r : info.reqs().getOrDefault(id, List.of())) {
                if (seen.add(r.itemName())) {
                    all.add(r);
                }
            }
        }
        TransportRule.Combined c = TransportRule.combine(all);
        boolean pickup = req.mode() == TransportMode.PICKUP;
        List<String> problems = TransportRule.problems(c, pickup, req.tempMonitored(), req.tempLow(), req.tempHigh(), req.damaged(), req.damageNote());

        TransportRecord r = new TransportRecord();
        r.setRecordNo(docNo.next("YS", time.today()));
        r.setDirection(req.direction());
        r.setMode(req.mode());
        r.setCarrier(carrier);
        r.setWaybillNo(blankToNull(req.waybillNo()));
        r.setShippedAt(shipped);
        r.setDeliveredAt(delivered);
        r.setReqTempMin(c.tempMin());
        r.setReqTempMax(c.tempMax());
        r.setReqMonitor(c.monitor() && !pickup);
        r.setReqText(cut(c.text(), 600));
        r.setTempMonitored(req.tempMonitored());
        r.setTempLow(req.tempMonitored() ? req.tempLow() : null);
        r.setTempHigh(req.tempMonitored() ? req.tempHigh() : null);
        r.setDevice(blankToNull(req.device()));
        r.setDamaged(req.damaged());
        r.setDamageNote(req.damaged() ? blankToNull(req.damageNote()) : null);
        r.setConforming(problems.isEmpty());
        r.setProblem(problems.isEmpty() ? null : cut(String.join("；", problems), 600));
        r.setNote(blankToNull(req.note()));
        r.setVoided(false);
        r.setCreatedBy(currentUser.usernameOrSystem());
        r.setCreatedAt(time.now());
        records.save(r);
        List<TransportDoc> links = new ArrayList<>();
        for (Long id : ids) {
            TransportDoc d = new TransportDoc();
            d.setRecordId(r.getId());
            d.setDocType(type);
            d.setDocId(id);
            d.setDupKey(TransportDoc.key(type, id));
            links.add(d);
        }
        docs.saveAll(links);

        String docsText = shortList(new ArrayList<>(docNos.values()), 5);
        Set<Long> batchIds = ids.stream().flatMap(id -> info.batchIds().getOrDefault(id, Set.of()).stream()).collect(Collectors.toCollection(LinkedHashSet::new));
        String batchText = ids.stream().map(id -> info.batches().get(id)).filter(t -> t != null && !t.isBlank()).distinct().collect(Collectors.joining("、"));
        String extra = "";
        if (!problems.isEmpty()) {
            String title = cut("运输条件不符合：" + req.direction().getLabel() + " " + docsText, 200);
            String desc = cut(r.getRecordNo() + "，" + req.mode().getLabel() + (carrier == null ? "" : " " + carrier) + (r.getWaybillNo() == null ? "" : " 运单 " + r.getWaybillNo())
                    + "，" + fmt(shipped) + " 发运" + (delivered == null ? "" : "、" + fmt(delivered) + " 到达") + "。运输条件：" + c.text() + "；途中温度："
                    + TransportRule.reading(req.tempMonitored(), req.tempLow(), req.tempHigh()) + "。不符合：" + String.join("；", problems)
                    + "。单据：" + String.join("、", docNos.values()) + (batchText.isEmpty() ? "" : "；批次：" + batchText)
                    + "。请评估对产品质量的影响，必要时通知收货方并处置这些批次。", 1000);
            Deviation dv = deviationService.createForTransport(r.getRecordNo(), deviationLevel, shipped.toLocalDate(), batchIds.size() == 1 ? batchIds.iterator().next() : null,
                    title, desc);
            r.setDeviationId(dv.getId());
            records.save(r);
            extra = "，不符合：" + String.join("；", problems) + "，生成偏差 " + dv.getDeviationNo();
        }
        audit.log("登记运输记录", r.getRecordNo(), req.direction().getLabel() + " " + docsText + "，" + req.mode().getLabel() + (carrier == null ? "" : " " + carrier)
                + "，" + fmt(shipped) + " 发运，条件 " + c.text() + "，途中温度 " + TransportRule.reading(req.tempMonitored(), req.tempLow(), req.tempHigh())
                + (problems.isEmpty() ? "，符合" : extra));
        return views(List.of(r)).get(0);
    }

    // ---------- 作废 ----------

    @Transactional
    public RecordView voidRecord(Long id, VoidReq req) {
        TransportRecord r = records.lockById(id).orElseThrow(() -> BizException.notFound("运输记录"));
        if (r.isVoided()) {
            throw new BizException("这条运输记录已经作废");
        }
        String reason = req.reason().trim();
        if (reason.isEmpty()) {
            throw new BizException("请写明作废原因");
        }
        List<TransportDoc> links = docs.lockByRecordId(r.getId());
        links.forEach(d -> d.setDupKey(null));
        docs.saveAll(links);
        r.setVoided(true);
        r.setVoidReason(reason);
        r.setVoidedBy(currentUser.usernameOrSystem());
        r.setVoidedAt(time.now());
        records.save(r);
        Deviation d = r.getDeviationId() == null ? null : deviationRepo.findById(r.getDeviationId()).orElse(null);
        audit.log("作废运输记录", r.getRecordNo(), r.getDirection().getLabel() + "，" + fmt(r.getShippedAt()) + " 发运，" + links.size() + " 张单据可以重新登记"
                + (d != null && d.getStatus() == CaseStatus.OPEN ? "；偏差 " + d.getDeviationNo() + " 请质量部门评估后关闭" : ""), reason);
        return views(List.of(r)).get(0);
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public RecordList list(LocalDate from, LocalDate to) {
        LocalDate t = to == null ? time.today() : to;
        LocalDate f = from == null ? t.minusDays(29) : from;
        if (f.isAfter(t)) {
            throw new BizException("开始日期不能晚于结束日期");
        }
        LocalDateTime start = f.atStartOfDay();
        LocalDateTime end = t.plusDays(1).atStartOfDay();
        List<TransportRecord> rows = records.findByShippedAtGreaterThanEqualAndShippedAtLessThanOrderByShippedAtDescIdDesc(start, end, PageRequest.of(0, LIST_CAP));
        return new RecordList(views(rows), records.countByShippedAtGreaterThanEqualAndShippedAtLessThan(start, end), LIST_CAP, f, t);
    }

    @Transactional(readOnly = true)
    public RecordView get(Long id) {
        return views(List.of(records.findById(id).orElseThrow(() -> BizException.notFound("运输记录")))).get(0);
    }

    /** 导出：期间内按发运时间的全部记录。 */
    @Transactional(readOnly = true)
    public List<RecordView> exportRows(LocalDate from, LocalDate to) {
        return views(records.findByShippedAtGreaterThanEqualAndShippedAtLessThanOrderByShippedAtAscIdAsc(from.atStartOfDay(), to.plusDays(1).atStartOfDay()));
    }

    /** 要登记、还没登记的单据（全部，不按最近 N 条）：出库单按仓库和日期分组，委托单逐张。 */
    @Transactional(readOnly = true)
    public Missing missing() {
        long active = reqs.countByActiveTrue();
        if (active == 0) {
            return new Missing(List.of(), graceDays, 0);
        }
        return new Missing(missingRows(names()), graceDays, active);
    }

    private List<MissingRow> missingRows(Names n) {
        List<TransportReq> active = reqs.findByActiveTrue();
        if (active.isEmpty()) {
            return List.of();
        }
        LocalDate today = time.today();
        LocalDate cutoff = today.minusDays(graceDays);
        LocalDate from = active.stream().map(TransportReq::getEffectiveFrom).min(Comparator.naturalOrder()).orElseThrow();
        List<MissingRow> out = new ArrayList<>();
        for (Object[] g : docs.missingSoGroups(from, today, TransportDocType.SO)) {
            Long wh = (Long) g[0];
            LocalDate d = (LocalDate) g[1];
            long cnt = ((Number) g[2]).longValue();
            boolean overdue = !d.isAfter(cutoff);
            out.add(new MissingRow(TransportDirection.OUTBOUND, TransportDirection.OUTBOUND.getLabel(), d, wh, warehouseName(n, wh), cnt, null, null, overdue,
                    d + " " + warehouseName(n, wh) + " 出库 " + cnt + " 张单据有设了运输条件的成品，还没有运输记录"
                            + (overdue ? "（超过 " + graceDays + " 天）" : "（出库后 " + graceDays + " 天内登记）")));
        }
        Map<Long, TransportReq> byItem = active.stream().collect(Collectors.toMap(TransportReq::getItemId, Function.identity()));
        Map<Long, Item> items = n.items();
        for (MfgOrder m : docs.unrecordedOemOrders(from.atStartOfDay(), today.plusDays(1).atStartOfDay(), true, MoType.OEM, MoStatus.COMPLETED, TransportDocType.MO)) {
            TransportReq q = byItem.get(m.getFgItemId());
            LocalDate d = m.getCompletedAt().toLocalDate();
            if (q == null || q.getEffectiveFrom().isAfter(d)) {
                continue;
            }
            boolean overdue = !d.isAfter(cutoff);
            out.add(new MissingRow(TransportDirection.INBOUND, TransportDirection.INBOUND.getLabel(), d, null, partyName(n, m.getFactoryId()), 1, m.getId(), m.getMoNo(),
                    overdue, d + " 完工入库的 " + name(items, m.getFgItemId()) + "（" + partyName(n, m.getFactoryId()) + "）还没有到仓运输记录"
                    + (overdue ? "（超过 " + graceDays + " 天）" : "（完工后 " + graceDays + " 天内登记）")));
        }
        out.sort(Comparator.comparing(MissingRow::date).thenComparing(MissingRow::direction));
        return out;
    }

    /** 总览待办：超过登记期限还没登记的单据；不符合、偏差还没关闭的记录。 */
    @Transactional(readOnly = true)
    public List<TodoRow> todos() {
        List<TodoRow> out = new ArrayList<>();
        if (reqs.countByActiveTrue() > 0) {
            for (MissingRow m : missingRows(names())) {
                if (m.overdue()) {
                    out.add(new TodoRow("MISSING", m.direction() == TransportDirection.OUTBOUND ? "销售发运 " + m.place() : m.place(),
                            m.docNo() == null ? m.date().toString() : m.docNo(), m.detail()));
                }
            }
        }
        List<TransportRecord> bad = records.nonconformingWithDeviation(CaseStatus.OPEN);
        if (!bad.isEmpty()) {
            Map<Long, Deviation> ds = deviationRepo.findAllById(bad.stream().map(TransportRecord::getDeviationId).toList()).stream()
                    .collect(Collectors.toMap(Deviation::getId, Function.identity()));
            for (TransportRecord r : bad) {
                Deviation d = ds.get(r.getDeviationId());
                out.add(new TodoRow("NONCONFORM", r.getDirection().getLabel(), r.getRecordNo(), fmt(r.getShippedAt()) + " 发运，" + r.getProblem()
                        + (d == null ? "" : "，偏差 " + d.getDeviationNo() + " 还没关闭")));
            }
        }
        return out;
    }

    // ---------- 视图 ----------

    private List<RecordView> views(List<TransportRecord> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Names n = names();
        Map<Long, List<TransportDoc>> byRecord = docs.findByRecordIdIn(list.stream().map(TransportRecord::getId).toList()).stream()
                .collect(Collectors.groupingBy(TransportDoc::getRecordId));
        Set<Long> soIds = new LinkedHashSet<>();
        Set<Long> moIds = new LinkedHashSet<>();
        byRecord.values().forEach(ds -> ds.forEach(d -> (d.getDocType() == TransportDocType.SO ? soIds : moIds).add(d.getDocId())));
        Map<Long, DocView> soViews = new HashMap<>();
        if (!soIds.isEmpty()) {
            List<SalesOrder> sos = salesOrders.findAllById(soIds);
            DocInfo info = soInfo(sos, n);
            sos.forEach(s -> soViews.put(s.getId(), new DocView(TransportDocType.SO, TransportDocType.SO.getLabel(), s.getId(), s.getSoNo(), s.getOrderDate(),
                    partyName(n, s.getCustomerId()), warehouseName(n, s.getWarehouseId()), info.items().get(s.getId()), info.batches().get(s.getId()))));
        }
        Map<Long, DocView> moViews = new HashMap<>();
        if (!moIds.isEmpty()) {
            List<MfgOrder> mos = mfgOrders.findAllById(moIds);
            DocInfo info = moInfo(mos, n);
            mos.forEach(m -> moViews.put(m.getId(), new DocView(TransportDocType.MO, TransportDocType.MO.getLabel(), m.getId(), m.getMoNo(),
                    m.getCompletedAt() == null ? null : m.getCompletedAt().toLocalDate(), partyName(n, m.getFactoryId()), null,
                    info.items().get(m.getId()), info.batches().get(m.getId()))));
        }
        Map<Long, Deviation> ds = deviationRepo.findAllById(list.stream().map(TransportRecord::getDeviationId).filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(Deviation::getId, Function.identity()));
        return list.stream().map(r -> {
            List<DocView> dv = byRecord.getOrDefault(r.getId(), List.of()).stream()
                    .map(d -> (d.getDocType() == TransportDocType.SO ? soViews : moViews).get(d.getDocId())).filter(Objects::nonNull).toList();
            Deviation d = r.getDeviationId() == null ? null : ds.get(r.getDeviationId());
            return new RecordView(r.getId(), r.getRecordNo(), r.getDirection(), r.getDirection().getLabel(), r.getMode(), r.getMode().getLabel(), r.getCarrier(),
                    r.getWaybillNo(), r.getShippedAt(), r.getDeliveredAt(), r.getReqText(), r.isReqMonitor(), r.isTempMonitored(), r.getTempLow(), r.getTempHigh(),
                    TransportRule.reading(r.isTempMonitored(), r.getTempLow(), r.getTempHigh()), r.getDevice(), r.isDamaged(), r.getDamageNote(), r.isConforming(),
                    r.getProblem(), r.getDeviationId(), d == null ? null : d.getDeviationNo(), d == null ? null : d.getStatus(),
                    d == null ? null : d.getStatus().getLabel(), r.getNote(), r.isVoided(), r.getVoidReason(), r.getVoidedBy(), r.getVoidedAt(), r.getCreatedBy(),
                    r.getCreatedAt(), dv, docsText(dv));
        }).toList();
    }

    private static String docsText(List<DocView> dv) {
        if (dv.isEmpty()) {
            return "";
        }
        DocView first = dv.get(0);
        String party = dv.stream().map(DocView::partyName).filter(Objects::nonNull).distinct().count() == 1 ? first.partyName() : null;
        return shortList(dv.stream().map(DocView::docNo).toList(), 3) + (party == null ? "，" + dv.stream().map(DocView::partyName).filter(Objects::nonNull).distinct().count() + " 家" : "，" + party);
    }

    // ---------- 工具 ----------

    /** 主数据一次载入（列表视图不逐条查往来单位和仓库）。 */
    private record Names(Map<Long, Partner> partners, Map<Long, Warehouse> warehouses, Map<Long, Item> items) {
    }

    private Names names() {
        return new Names(lookup.partnerMap(), lookup.warehouseMap(), lookup.itemMap());
    }

    private static String partyName(Names n, Long id) {
        Partner p = id == null ? null : n.partners().get(id);
        return p == null ? null : p.getName();
    }

    private static String warehouseName(Names n, Long id) {
        Warehouse w = id == null ? null : n.warehouses().get(id);
        return w == null ? null : w.getName();
    }

    private static String name(Map<Long, Item> items, Long id) {
        Item i = items.get(id);
        return i == null ? String.valueOf(id) : i.getName();
    }

    static String shortList(List<String> list, int max) {
        return list.size() <= max ? String.join("、", list) : String.join("、", list.subList(0, max)) + " 等 " + list.size() + " 张";
    }

    private static String plain(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    private static String fmt(LocalDateTime t) {
        return t == null ? "" : t.format(MIN);
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
