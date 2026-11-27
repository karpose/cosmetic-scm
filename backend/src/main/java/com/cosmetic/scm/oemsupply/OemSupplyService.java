package com.cosmetic.scm.oemsupply;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.MoStatus;
import com.cosmetic.scm.domain.MoType;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.SpecAckMethod;
import com.cosmetic.scm.domain.SpecStatus;
import com.cosmetic.scm.domain.rules.SpecLimit;
import com.cosmetic.scm.domain.rules.SupplySpecRule;
import com.cosmetic.scm.master.BomLineRepository;
import com.cosmetic.scm.master.BomRepository;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.plm.FormulaLineRepository;
import com.cosmetic.scm.production.MfgOrder;
import com.cosmetic.scm.production.MfgOrderRepository;
import com.cosmetic.scm.quality.QualitySpec;
import com.cosmetic.scm.quality.QualitySpecLine;
import com.cosmetic.scm.quality.QualitySpecLineRepository;
import com.cosmetic.scm.quality.QualitySpecRepository;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.CurrentUser;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

/**
 * 委托方提供物料验收标准（《化妆品生产质量管理规范》第五十四条第二款；检查要点委托生产版第 12 项）。
 * 在做的委托单（已计划、已发料）里品牌供料的原料、包材，把现行质量标准和原料成分（INCI、CAS 号）提供给受托厂，
 * 受托厂在门户逐版确认收到，不用门户的由质量部门登记线下交接。换版后要重新确认。
 * 默认只提醒；打开 scm.oem.require-spec-ack-for-issue 后，委托单首次发料前要都确认过（`ProductionService.issue`）。不拦放行。确认记录只增不改。写操作先锁标准行（事务第一句），所以路径带标准 id。
 */
@Service
@RequiredArgsConstructor
public class OemSupplyService {

    /** 在做的委托单。 */
    static final List<MoStatus> OPEN = List.of(MoStatus.PLANNED, MoStatus.ISSUED);

    private final MfgOrderRepository orders;
    private final FormulaLineRepository formulaLines;
    private final BomRepository boms;
    private final BomLineRepository bomLines;
    private final QualitySpecRepository specs;
    private final QualitySpecLineRepository specLines;
    private final OemSpecAckRepository acks;
    private final MasterLookup lookup;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    @org.springframework.beans.factory.annotation.Value("${scm.oem.require-spec-ack-for-issue:false}")
    private boolean requireAckForIssue;

    public record OfflineReq(@NotNull(message = "请选择受托生产企业") Long factoryId,
                             @NotBlank(message = "请填写受托厂的接收人") @Size(max = 64, message = "接收人最多 64 个字") String receiver,
                             @Size(max = 300, message = "备注最多 300 个字") String note) {
    }

    public record PortalAckReq(@Size(max = 300, message = "备注最多 300 个字") String note) {
    }

    public record AckView(Long id, Long factoryId, String factoryName, Long specId, String specNo, int specVersion, Long itemId, String itemCode, String itemName,
                          SpecAckMethod method, String methodLabel, String receiver, String note, String createdBy, LocalDateTime createdAt) {
    }

    /** 一家受托厂的一个品牌供料物料。specId 为空表示还没有生效的质量标准；lastAck 是这家受托厂最近一次确认（可能是旧版）。 */
    public record Row(Long factoryId, String factoryCode, String factoryName, Long itemId, String itemCode, String itemName, String itemTypeLabel, String inci,
                      String casNo, boolean compositionMissing, Long specId, String specNo, Integer specVersion, SupplySpecRule.State state, String stateLabel,
                      AckView lastAck, List<String> moNos) {
    }

    /** requireAckForIssue：开关 scm.oem.require-spec-ack-for-issue，打开后受托厂没确认就不能发料。 */
    public record Overview(List<Row> rows, long factories, long noSpec, long awaiting, long acked, long compositionMissing, List<AckView> recentAcks,
                           boolean requireAckForIssue) {
    }

    public record SpecLineView(int lineNo, String name, String method, String limitText, String criteria) {
    }

    /** 门户看到的一份验收标准：只有标准内容和本厂的确认情况，不含是否可采用供方报告这类内部安排。 */
    public record PortalRow(Long itemId, String itemCode, String itemName, String itemTypeLabel, String unit, String inci, String casNo, boolean compositionMissing,
                            Long specId, String specNo, Integer specVersion, LocalDateTime approvedAt, String samplingPlan, List<SpecLineView> lines,
                            SupplySpecRule.State state, String stateLabel, String ackedBy, LocalDateTime ackedAt, List<String> moNos) {
    }

    public record ExportRow(String factoryName, String itemCode, String itemName, String itemType, String inci, String casNo, String specNo, String specVersion,
                            String state, String lastAck, String moNos) {
    }

    // ---------- 内部 ----------

    @Transactional(readOnly = true)
    public Overview overview() {
        List<Row> rows = rows(openOrders());
        Set<Long> factories = rows.stream().map(Row::factoryId).collect(Collectors.toSet());
        long noSpec = rows.stream().filter(r -> r.state() == SupplySpecRule.State.NO_SPEC).count();
        long awaiting = rows.stream().filter(r -> r.state().awaitingAck()).count();
        long acked = rows.stream().filter(r -> r.state() == SupplySpecRule.State.ACKED).count();
        long comp = rows.stream().filter(Row::compositionMissing).map(Row::itemId).distinct().count();
        return new Overview(rows, factories.size(), noSpec, awaiting, acked, comp, ackViews(acks.findTop100ByOrderByIdDesc()), requireAckForIssue);
    }

    /** 总览待办：在做的委托单里有现行标准、受托厂还没确认这一版的。没有标准的不进待办（在页面和自查第 12 项里列出）。 */
    @Transactional(readOnly = true)
    public List<Row> awaitingAck() {
        return rows(openOrders()).stream().filter(r -> r.state().awaitingAck()).toList();
    }

    @Transactional(readOnly = true)
    public List<ExportRow> exportRows() {
        return rows(openOrders()).stream().map(r -> new ExportRow(r.factoryName(), r.itemCode(), r.itemName(), r.itemTypeLabel(), nz(r.inci()), nz(r.casNo()),
                r.specNo() == null ? "" : r.specNo(), r.specVersion() == null ? "" : "V" + r.specVersion(), r.stateLabel()
                        + (r.compositionMissing() ? "；原料没有登记 INCI 名称" : ""),
                r.lastAck() == null ? "" : "V" + r.lastAck().specVersion() + " " + r.lastAck().methodLabel() + "，" + r.lastAck().receiver() + "，" + r.lastAck().createdAt().toLocalDate(),
                String.join("、", r.moNos()))).toList();
    }

    /** 导出用：期间内的全部确认记录。 */
    @Transactional(readOnly = true)
    public List<AckView> ackRecords(LocalDate from, LocalDate to) {
        return ackViews(acks.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderById(from.atStartOfDay(), to.plusDays(1).atStartOfDay()));
    }

    /** 质量部门登记线下交接（受托厂不用门户时）。先锁标准行（事务第一句），和换版、门户确认串行。 */
    @Transactional
    public AckView recordOffline(Long specId, OfflineReq req) {
        QualitySpec s = specs.lockById(specId).orElseThrow(() -> BizException.notFound("质量标准"));
        requireEffective(s);
        Partner f = lookup.partner(req.factoryId());
        if (f.getKind() != PartnerKind.OEM) {
            throw new BizException(f.getName() + " 不是受托生产企业");
        }
        acks.findByFactoryIdAndSpecId(f.getId(), s.getId()).ifPresent(a -> {
            throw new BizException(f.getName() + " 已经确认过 " + s.getSpecNo() + " 第 " + s.getVersionNo() + " 版（" + a.getMethod().getLabel() + "，"
                    + a.getReceiver() + "），不用重复登记");
        });
        Item item = lookup.item(s.getItemId());
        OemSpecAck a = save(f, s, SpecAckMethod.OFFLINE, req.receiver().trim(), blankToNull(req.note()));
        audit.log("登记物料验收标准线下交接", s.getSpecNo(), f.getName() + " 收到 " + item.getName() + " 第 " + s.getVersionNo() + " 版验收标准，接收人 "
                + a.getReceiver(), a.getNote());
        return ackViews(List.of(a)).get(0);
    }

    // ---------- 门户 ----------

    /** 本厂在做的委托单里品牌供料的物料和它们的现行验收标准。 */
    @Transactional(readOnly = true)
    public List<PortalRow> mine() {
        Long factoryId = currentUser.portalPartnerId();
        return portalRows(factoryId, orders.findByFactoryIdAndMoTypeOrderByIdDesc(factoryId, MoType.OEM).stream().filter(m -> OPEN.contains(m.getStatus())).toList());
    }

    /**
     * 受托厂确认收到一版验收标准。先锁标准行（事务第一句）再核对归属：不是本厂在做的委托单要用的物料一律当“不存在”。
     * 同一版已确认过的直接返回（重复点击不报错）。
     */
    @Transactional
    public List<PortalRow> ack(Long specId, PortalAckReq req) {
        QualitySpec s = specs.lockById(specId).orElseThrow(() -> BizException.notFound("物料验收标准"));
        AppUser u = currentUser.get();
        Long factoryId = currentUser.portalPartnerId();
        List<MfgOrder> mine = orders.findByFactoryIdAndMoTypeOrderByIdDesc(factoryId, MoType.OEM).stream().filter(m -> OPEN.contains(m.getStatus())).toList();
        boolean needed = SuppliedMaterials.of(mine, formulaLines, boms, bomLines).stream().anyMatch(n -> n.itemId().equals(s.getItemId()));
        if (!needed) {
            throw BizException.notFound("物料验收标准");
        }
        requireEffective(s);
        if (acks.findByFactoryIdAndSpecId(factoryId, s.getId()).isEmpty()) {
            Partner f = lookup.partner(factoryId);
            String receiver = u.getDisplayName() == null || u.getDisplayName().isBlank() ? u.getUsername() : u.getDisplayName();
            OemSpecAck a = save(f, s, SpecAckMethod.PORTAL, receiver.length() > 64 ? receiver.substring(0, 64) : receiver, req == null ? null : blankToNull(req.note()));
            audit.log("受托厂确认物料验收标准", s.getSpecNo(), f.getName() + " 确认收到 " + lookup.item(s.getItemId()).getName() + " 第 " + s.getVersionNo() + " 版验收标准",
                    a.getNote());
        }
        return portalRows(factoryId, mine);
    }

    // ---------- 组装 ----------

    private List<MfgOrder> openOrders() {
        return orders.findByMoTypeAndStatusIn(MoType.OEM, OPEN);
    }

    private OemSpecAck save(Partner f, QualitySpec s, SpecAckMethod method, String receiver, String note) {
        OemSpecAck a = new OemSpecAck();
        a.setFactoryId(f.getId());
        a.setSpecId(s.getId());
        a.setItemId(s.getItemId());
        a.setSpecVersion(s.getVersionNo());
        a.setMethod(method);
        a.setReceiver(receiver);
        a.setNote(note);
        a.setCreatedBy(currentUser.usernameOrSystem());
        a.setCreatedAt(time.now());
        try {
            return acks.saveAndFlush(a);
        } catch (DataIntegrityViolationException e) {
            // 锁住标准行后才查重，正常到不了这里；唯一约束兜底
            throw new BizException(f.getName() + " 已经确认过这一版，请刷新");
        }
    }

    private static void requireEffective(QualitySpec s) {
        if (s.getStatus() != SpecStatus.EFFECTIVE) {
            throw new BizException(s.getSpecNo() + " 第 " + s.getVersionNo() + " 版" + (s.getStatus() == SpecStatus.SUPERSEDED ? "已经换版" : "还没生效")
                    + "，请刷新后确认现行版本");
        }
    }

    private List<Row> rows(List<MfgOrder> mos) {
        List<SuppliedMaterials.Need> needs = SuppliedMaterials.of(mos, formulaLines, boms, bomLines);
        if (needs.isEmpty()) {
            return List.of();
        }
        Set<Long> itemIds = needs.stream().map(SuppliedMaterials.Need::itemId).collect(Collectors.toSet());
        Set<Long> factoryIds = needs.stream().map(SuppliedMaterials.Need::factoryId).collect(Collectors.toSet());
        Map<Long, QualitySpec> current = SuppliedMaterials.current(specs.findByItemIdIn(itemIds));
        Map<String, List<OemSpecAck>> byPair = acks.findByFactoryIdInAndItemIdIn(factoryIds, itemIds).stream()
                .collect(Collectors.groupingBy(a -> SuppliedMaterials.key(a.getFactoryId(), a.getItemId())));
        Map<Long, Item> im = lookup.itemMap();
        Map<Long, Partner> pm = lookup.partnerMap();
        Map<Long, QualitySpec> specById = new HashMap<>();
        current.values().forEach(s -> specById.put(s.getId(), s));
        List<Row> out = new ArrayList<>();
        for (SuppliedMaterials.Need n : needs) {
            Item i = im.get(n.itemId());
            Partner f = pm.get(n.factoryId());
            if (i == null || f == null) {
                continue;
            }
            QualitySpec s = current.get(n.itemId());
            List<OemSpecAck> got = byPair.getOrDefault(SuppliedMaterials.key(n.factoryId(), n.itemId()), List.of());
            SupplySpecRule.State st = SupplySpecRule.state(s == null ? null : s.getVersionNo(), got.stream().map(OemSpecAck::getSpecVersion).toList());
            OemSpecAck last = got.stream().max(Comparator.comparing(OemSpecAck::getSpecVersion).thenComparing(OemSpecAck::getId)).orElse(null);
            out.add(new Row(f.getId(), f.getCode(), f.getName(), i.getId(), i.getCode(), i.getName(), i.getItemType().getLabel(), i.getInci(), i.getCasNo(),
                    SupplySpecRule.compositionMissing(i.getItemType() == ItemType.RM, i.getInci()), s == null ? null : s.getId(), s == null ? null : s.getSpecNo(),
                    s == null ? null : s.getVersionNo(), st, st.getLabel(), last == null ? null : ackView(last, im, pm, Map.of()),
                    n.orders().stream().map(MfgOrder::getMoNo).toList()));
        }
        out.sort(Comparator.comparing(Row::factoryName).thenComparing(Row::itemCode));
        return out;
    }

    private List<PortalRow> portalRows(Long factoryId, List<MfgOrder> mine) {
        List<Row> rows = rows(mine).stream().filter(r -> r.factoryId().equals(factoryId)).toList();
        Set<Long> specIds = rows.stream().map(Row::specId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, QualitySpec> specMap = specIds.isEmpty() ? Map.of() : specs.findAllById(specIds).stream().collect(Collectors.toMap(QualitySpec::getId, s -> s));
        Map<Long, List<QualitySpecLine>> lines = specIds.isEmpty() ? Map.of()
                : specLines.findBySpecIdIn(specIds).stream().collect(Collectors.groupingBy(QualitySpecLine::getSpecId));
        Map<Long, OemSpecAck> ackOfSpec = specIds.isEmpty() ? Map.of()
                : acks.findByFactoryIdInAndItemIdIn(List.of(factoryId), rows.stream().map(Row::itemId).toList()).stream()
                        .filter(a -> specIds.contains(a.getSpecId())).collect(Collectors.toMap(OemSpecAck::getSpecId, a -> a, (a, b) -> a));
        Map<Long, Item> im = lookup.itemMap();
        return rows.stream().map(r -> {
            QualitySpec s = r.specId() == null ? null : specMap.get(r.specId());
            OemSpecAck a = r.specId() == null ? null : ackOfSpec.get(r.specId());
            List<SpecLineView> lv = s == null ? List.of() : lines.getOrDefault(s.getId(), List.of()).stream().sorted(Comparator.comparing(QualitySpecLine::getLineNo))
                    .map(l -> new SpecLineView(l.getLineNo(), l.getName(), l.getMethod(), SpecLimit.text(l.getMinValue(), l.getMaxValue(), l.getUnit()), l.getCriteria()))
                    .toList();
            Item i = im.get(r.itemId());
            return new PortalRow(r.itemId(), r.itemCode(), r.itemName(), r.itemTypeLabel(), i == null ? null : i.getUnit(), r.inci(), r.casNo(), r.compositionMissing(),
                    r.specId(), r.specNo(), r.specVersion(), s == null ? null : s.getApprovedAt(), s == null ? null : s.getSamplingPlan(), lv, r.state(), r.stateLabel(),
                    a == null ? null : a.getReceiver(), a == null ? null : a.getCreatedAt(), r.moNos());
        }).toList();
    }

    private List<AckView> ackViews(List<OemSpecAck> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, QualitySpec> sm = specs.findAllById(list.stream().map(OemSpecAck::getSpecId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(QualitySpec::getId, s -> s));
        Map<Long, Item> im = lookup.itemMap();
        Map<Long, Partner> pm = lookup.partnerMap();
        return list.stream().map(a -> ackView(a, im, pm, sm)).toList();
    }

    private static AckView ackView(OemSpecAck a, Map<Long, Item> im, Map<Long, Partner> pm, Map<Long, QualitySpec> sm) {
        Item i = im.get(a.getItemId());
        Partner f = pm.get(a.getFactoryId());
        QualitySpec s = sm.get(a.getSpecId());
        return new AckView(a.getId(), a.getFactoryId(), f == null ? "-" : f.getName(), a.getSpecId(), s == null ? null : s.getSpecNo(), a.getSpecVersion(),
                a.getItemId(), i == null ? "-" : i.getCode(), i == null ? "-" : i.getName(), a.getMethod(), a.getMethod().getLabel(), a.getReceiver(), a.getNote(),
                a.getCreatedBy(), a.getCreatedAt());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
