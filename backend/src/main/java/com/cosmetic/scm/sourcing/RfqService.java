package com.cosmetic.scm.sourcing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.DocNoService;
import com.cosmetic.scm.common.Qty;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.RfqStatus;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.purchase.PoLine;
import com.cosmetic.scm.purchase.PoLineRepository;
import com.cosmetic.scm.purchase.PurchaseOrderRepository;
import com.cosmetic.scm.purchase.PurchaseService;
import com.cosmetic.scm.purchase.PurchaseService.CreatePoReq;
import com.cosmetic.scm.purchase.PurchaseService.PoLineReq;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.CurrentUser;
import com.cosmetic.scm.security.SignatureService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 询价比价：发询价 → 供应商报价（互相看不到） → 开标前内部也看不到价格 → 比价（支持历史采购价对比与发起多轮议价） → 定标生成采购单。
 * 定标要签批，定标人不能是询价人；不足 3 家报价或没选最低价要写原因。
 */
@Service
@RequiredArgsConstructor
public class RfqService {

    /** 比价的最少家数，不足时定标要写原因。 */
    public static final int MIN_QUOTES = 3;

    private final RfqRepository rfqs;
    private final RfqLineRepository lines;
    private final RfqSupplierRepository invited;
    private final RfqQuoteRepository quotes;
    private final PurchaseService purchase;
    private final PurchaseOrderRepository orders;
    private final PoLineRepository poLines;
    private final MasterLookup lookup;
    private final SignatureService signature;
    private final DocNoService docNo;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;
    private final com.cosmetic.scm.master.RegulatoryService regulatory;

    public record RfqLineReq(@NotNull Long itemId, @NotNull @Positive @Qty BigDecimal qty) {
    }

    public record CreateRfqReq(@NotBlank(message = "请填写询价标题") @Size(max = 120) String title,
                               @NotNull(message = "请选择报价截止日期") LocalDate deadline,
                               @NotEmpty(message = "至少邀请一家供应商") List<Long> supplierIds,
                               @NotEmpty(message = "至少一行物料") @Valid List<RfqLineReq> lines) {
    }

    public record NextRoundReq(@NotNull(message = "请选择新截止日期") LocalDate deadline,
                               @NotBlank(message = "请填写本轮议价说明或目标价要求") @Size(max = 300) String note) {
    }

    public record CancelReq(@NotBlank(message = "请填写取消原因") @Size(max = 300) String reason) {
    }

    public record AwardLineReq(@NotNull Long rfqLineId, @NotNull Long supplierId) {
    }

    public record AwardReq(@NotEmpty(message = "至少定标一行") @Valid List<AwardLineReq> lines,
                           @Size(max = 300) String reason,
                           @NotBlank(message = "请输入签批密码") String password) {
    }

    public record QuoteLineReq(@NotNull Long rfqLineId,
                               @NotNull(message = "请填写单价") @Positive(message = "单价要大于 0")
                               @Digits(integer = 14, fraction = 4, message = "单价最多 14 位整数、4 位小数") BigDecimal unitPrice,
                               @NotNull(message = "请填写税率") @DecimalMin(value = "0", message = "税率不能为负")
                               @DecimalMax(value = "100", message = "税率不能超过 100")
                               @Digits(integer = 3, fraction = 2, message = "税率最多 2 位小数") BigDecimal taxRate,
                               @NotNull(message = "请填写交货天数") @Min(value = 0, message = "交货天数不能为负")
                               @Max(value = 3650, message = "交货天数太大") Integer leadDays,
                               @NotNull(message = "请选择报价有效期") LocalDate validUntil,
                               @Size(max = 300) String note) {
    }

    public record QuoteReq(@NotEmpty(message = "至少报一行") @Valid List<QuoteLineReq> lines) {
    }

    public record QuoteView(Long supplierId, String supplierName, int roundNo, BigDecimal prevUnitPrice,
                            BigDecimal unitPrice, BigDecimal taxRate, BigDecimal amount, int leadDays,
                            LocalDate validUntil, String note, boolean lowest, boolean awarded, String poNo) {
    }

    public record RfqLineView(Long id, String itemCode, String itemName, String unit, BigDecimal qty,
                              BigDecimal lastPoPrice, List<QuoteView> quotes) {
    }

    public record InvitedView(Long supplierId, String supplierName, boolean quoted) {
    }

    /** sealed：还没开标，quotes 是空的，只知道谁报了。canAward：可以比价定标或发起下一轮议价。 */
    public record RfqView(Long id, String rfqNo, String title, LocalDate deadline, int roundNo, String roundNote,
                          RfqStatus status, String statusLabel, boolean sealed, boolean canAward, String closeReason,
                          String awardReason, String awardedBy, LocalDateTime awardedAt,
                          String createdBy, LocalDateTime createdAt,
                          List<RfqLineView> lines, List<InvitedView> suppliers) {
    }

    public record MyQuote(int roundNo, BigDecimal prevUnitPrice, BigDecimal unitPrice, BigDecimal taxRate,
                          int leadDays, LocalDate validUntil, String note, boolean awarded, String poNo) {
    }

    public record PortalLine(Long id, String itemCode, String itemName, String unit, BigDecimal qty,
                             MyQuote myQuote) {
    }

    /** 门户看到的询价：只有自己的报价，不含别家和定标原因。 */
    public record PortalRfq(Long id, String rfqNo, String title, LocalDate deadline, int roundNo, String roundNote,
                            String stateLabel, boolean canQuote, List<PortalLine> lines) {
    }

    private boolean sealed(Rfq r) {
        return r.getStatus() == RfqStatus.OPEN && !time.today().isAfter(r.getDeadline());
    }

    private boolean canAward(Rfq r) {
        return r.getStatus() == RfqStatus.CLOSED
                || (r.getStatus() == RfqStatus.OPEN && time.today().isAfter(r.getDeadline()));
    }

    @Transactional(readOnly = true)
    public List<RfqView> list() {
        return views(rfqs.findTop300ByOrderByIdDesc());
    }

    @Transactional
    public RfqView create(CreateRfqReq req) {
        LocalDate today = time.today();
        if (req.deadline().isBefore(today)) {
            throw new BizException("报价截止日期不能早于今天");
        }
        Set<Long> suppliers = new java.util.LinkedHashSet<>(req.supplierIds());
        for (Long id : suppliers) {
            Partner p = lookup.partner(id);
            if (p.getKind() != PartnerKind.SUPPLIER) {
                throw new BizException(p.getName() + " 不是物料供应商");
            }
            if (!p.usable(today)) {
                throw new BizException(p.getName() + " 不在合格名录或资质已过期，不能邀请报价");
            }
        }
        Set<Long> seen = new HashSet<>();
        for (RfqLineReq l : req.lines()) {
            Item item = lookup.item(l.itemId());
            if (item.getItemType() == ItemType.FG) {
                throw new BizException("只能询原料、包材或半成品加工，" + item.getName() + " 是产成品");
            }
            if (regulatory.isBanned(item)) {
                throw new BizException(item.getName() + " 为禁用原料，不能询价");
            }
            if (!item.isActive()) {
                throw new BizException(item.getName() + " 已停用");
            }
            if (!seen.add(l.itemId())) {
                throw new BizException(item.getName() + " 重复，请合并为一行");
            }
        }
        Rfq r = new Rfq();
        r.setRfqNo(docNo.next("RFQ", today));
        r.setTitle(req.title().trim());
        r.setDeadline(req.deadline());
        r.setRoundNo(1);
        r.setStatus(RfqStatus.OPEN);
        r.setCreatedBy(currentUser.usernameOrSystem());
        r.setCreatedAt(time.now());
        rfqs.save(r);
        for (RfqLineReq l : req.lines()) {
            RfqLine line = new RfqLine();
            line.setRfqId(r.getId());
            line.setItemId(l.itemId());
            line.setQty(l.qty());
            lines.save(line);
        }
        for (Long id : suppliers) {
            RfqSupplier s = new RfqSupplier();
            s.setRfqId(r.getId());
            s.setSupplierId(id);
            invited.save(s);
        }
        audit.log("发起询价", r.getRfqNo(), r.getTitle() + "，邀请 " + suppliers.size() + " 家，"
                + req.lines().size() + " 行，截止 " + r.getDeadline());
        return views(List.of(r)).get(0);
    }

    /** 提前开标：邀请的供应商都报完所有行才可以，否则还没报的人就没机会了。 */
    @Transactional
    public RfqView open(Long id) {
        Rfq r = rfqs.lockById(id).orElseThrow(() -> BizException.notFound("询价单"));
        if (r.getStatus() != RfqStatus.OPEN) {
            throw new BizException("询价单 " + r.getRfqNo() + " 当前为" + r.getStatus().getLabel() + "，不能开标");
        }
        int currentRound = r.getRoundNo() <= 0 ? 1 : r.getRoundNo();
        List<RfqLine> ls = lines.findByRfqIdIn(List.of(id));
        Set<String> got = quotes.findByRfqIdIn(List.of(id)).stream()
                .filter(q -> q.getRoundNo() == currentRound)
                .map(q -> q.getRfqLineId() + ":" + q.getSupplierId())
                .collect(Collectors.toSet());
        if (sealed(r)) {
            for (RfqSupplier s : invited.findByRfqIdIn(List.of(id))) {
                for (RfqLine l : ls) {
                    if (!got.contains(l.getId() + ":" + s.getSupplierId())) {
                        throw new BizException(lookup.partner(s.getSupplierId()).getName()
                                + " 在第 " + currentRound + " 轮还没报完，不能提前开标。可以等到截止日期");
                    }
                }
            }
        }
        r.setStatus(RfqStatus.CLOSED);
        rfqs.save(r);
        audit.log("询价开标", r.getRfqNo(), r.getTitle() + "（第 " + currentRound + " 轮）");
        return views(List.of(r)).get(0);
    }

    /**
     * 发起下一轮议价：已开标或已截止待定标时，若报价偏高，采购可设定新截止日期与议价说明，
     * 将轮次 +1 并把现有单价快照保存到 prevUnitPrice，重新进入封标报价状态。
     */
    @Transactional
    public RfqView nextRound(Long id, NextRoundReq req) {
        Rfq r = rfqs.lockById(id).orElseThrow(() -> BizException.notFound("询价单"));
        if (!canAward(r)) {
            throw new BizException(sealed(r) ? "当前轮次尚未开标或截止，不能发起下一轮议价"
                    : "询价单 " + r.getRfqNo() + " 当前为" + r.getStatus().getLabel() + "，不能发起下一轮议价");
        }
        LocalDate today = time.today();
        if (req.deadline().isBefore(today)) {
            throw new BizException("新报价截止日期不能早于今天");
        }
        int nextRoundNo = (r.getRoundNo() <= 0 ? 1 : r.getRoundNo()) + 1;
        for (RfqQuote q : quotes.findByRfqIdIn(List.of(id))) {
            q.setPrevUnitPrice(q.getUnitPrice());
            if (q.getValidUntil() != null && q.getValidUntil().isBefore(req.deadline())) {
                q.setValidUntil(req.deadline());
            }
            quotes.save(q);
        }
        r.setRoundNo(nextRoundNo);
        r.setRoundNote(req.note().trim());
        r.setDeadline(req.deadline());
        r.setStatus(RfqStatus.OPEN);
        rfqs.save(r);
        audit.log("发起多轮议价", r.getRfqNo(), "进入第 " + nextRoundNo + " 轮议价，新截止日 " + req.deadline(), req.note().trim());
        return views(List.of(r)).get(0);
    }

    @Transactional
    public RfqView cancel(Long id, CancelReq req) {
        Rfq r = rfqs.lockById(id).orElseThrow(() -> BizException.notFound("询价单"));
        if (r.getStatus() == RfqStatus.AWARDED || r.getStatus() == RfqStatus.CANCELLED) {
            throw new BizException("询价单 " + r.getRfqNo() + " 当前为" + r.getStatus().getLabel() + "，不能取消");
        }
        r.setStatus(RfqStatus.CANCELLED);
        r.setCloseReason(req.reason().trim());
        rfqs.save(r);
        audit.log("取消询价", r.getRfqNo(), r.getTitle(), req.reason().trim());
        return views(List.of(r)).get(0);
    }

    @Transactional
    public RfqView award(Long id, AwardReq req) {
        Rfq r = rfqs.lockById(id).orElseThrow(() -> BizException.notFound("询价单"));
        if (!canAward(r)) {
            throw new BizException(sealed(r)
                    ? "还没开标，不能定标"
                    : "询价单 " + r.getRfqNo() + " 当前为" + r.getStatus().getLabel() + "，不能定标");
        }
        AppUser signer = signature.verify(req.password());
        if (signer.getUsername().equals(r.getCreatedBy())) {
            throw new BizException("定标人不能是询价人，请由另一位有权限的同事签批");
        }
        LocalDate today = time.today();
        List<RfqLine> ls = lines.findByRfqIdIn(List.of(id));
        Map<Long, Long> choice = new HashMap<>();
        for (AwardLineReq a : req.lines()) {
            if (choice.put(a.rfqLineId(), a.supplierId()) != null) {
                throw new BizException("同一行只能选一家供应商");
            }
        }
        // 可以只定一部分：某行没人报价，或价格都不合适。这些行不生成采购单
        if (!ls.stream().map(RfqLine::getId).collect(Collectors.toSet()).containsAll(choice.keySet())) {
            throw new BizException("定标行不属于询价单 " + r.getRfqNo());
        }
        List<RfqQuote> all = quotes.findByRfqIdIn(List.of(id));
        Map<Long, List<RfqQuote>> byLine = all.stream().collect(Collectors.groupingBy(RfqQuote::getRfqLineId));
        Map<Long, Item> items = lookup.itemMap();
        boolean needReason = false;
        Map<Long, List<RfqQuote>> bySupplier = new LinkedHashMap<>();
        List<String> detail = new ArrayList<>();
        for (RfqLine l : ls) {
            Long supplier = choice.get(l.getId());
            List<RfqQuote> qs = byLine.getOrDefault(l.getId(), List.of());
            if (supplier == null) {
                detail.add(items.get(l.getItemId()).getName() + " 未定标");
                // 有报价却不定，要说明为什么
                needReason = needReason || !qs.isEmpty();
                continue;
            }
            RfqQuote q = qs.stream().filter(x -> x.getSupplierId().equals(supplier)).findFirst()
                    .orElseThrow(() -> new BizException("所选供应商没有报 " + items.get(l.getItemId()).getName()));
            if (q.getValidUntil().isBefore(today)) {
                throw new BizException(lookup.partner(supplier).getName()
                        + " 对 " + items.get(l.getItemId()).getName() + " 的报价已于 " + q.getValidUntil() + " 过期");
            }
            BigDecimal lowest = qs.stream().map(RfqQuote::getUnitPrice).min(Comparator.naturalOrder()).orElseThrow();
            if (qs.size() < MIN_QUOTES || q.getUnitPrice().compareTo(lowest) > 0) {
                needReason = true;
            }
            bySupplier.computeIfAbsent(supplier, k -> new ArrayList<>()).add(q);
            detail.add(items.get(l.getItemId()).getName() + " 定 " + lookup.partner(supplier).getName());
        }
        String reason = req.reason() == null || req.reason().isBlank() ? null : req.reason().trim();
        if (needReason && reason == null) {
            throw new BizException("有物料不足 " + MIN_QUOTES + " 家报价、没有选最低价，或有报价却不定，请写明定标原因");
        }
        Map<Long, RfqLine> lineMap = ls.stream().collect(Collectors.toMap(RfqLine::getId, l -> l));
        List<String> poNos = new ArrayList<>();
        for (Map.Entry<Long, List<RfqQuote>> e : bySupplier.entrySet()) {
            int lead = e.getValue().stream().mapToInt(RfqQuote::getLeadDays).max().orElse(0);
            List<PoLineReq> poLineReqs = e.getValue().stream().map(q -> {
                RfqLine l = lineMap.get(q.getRfqLineId());
                return new PoLineReq(l.getItemId(), l.getQty(), q.getUnitPrice(), q.getTaxRate());
            }).toList();
            PurchaseService.PoView po = purchase.create(new CreatePoReq(e.getKey(), poLineReqs, today.plusDays(lead)));
            poNos.add(po.poNo());
            for (RfqQuote q : e.getValue()) {
                q.setAwarded(true);
                q.setPoId(po.id());
                quotes.save(q);
            }
        }
        r.setStatus(RfqStatus.AWARDED);
        r.setAwardReason(reason);
        r.setAwardedBy(signer.getUsername());
        r.setAwardedAt(time.now());
        rfqs.save(r);
        audit.logMoney("询价定标", r.getRfqNo(),
                String.join("，", detail) + "；生成采购单 " + String.join("，", poNos), reason);
        return views(List.of(r)).get(0);
    }

    // ----------- 供应商门户 -----------

    @Transactional(readOnly = true)
    public List<PortalRfq> myRfqs() {
        return portalViews(currentUser.portalPartnerId());
    }

    /** 截止前可以反复改。审计不记价格：开标前审计日志里也不能看到。 */
    @Transactional
    public List<PortalRfq> submitQuote(Long rfqId, QuoteReq req) {
        // 必须是事务第一句，否则读不到别人刚提交的开标和取消
        Rfq r = rfqs.lockById(rfqId).orElseThrow(() -> BizException.notFound("询价单"));
        Long mine = currentUser.portalPartnerId();
        if (invited.findByRfqIdIn(List.of(rfqId)).stream().noneMatch(s -> mine.equals(s.getSupplierId()))) {
            throw BizException.notFound("询价单");
        }
        if (!sealed(r)) {
            throw new BizException("询价单 " + r.getRfqNo() + " 已截止或已结束，不能再报价");
        }
        int currentRound = r.getRoundNo() <= 0 ? 1 : r.getRoundNo();
        Set<Long> valid = lines.findByRfqIdIn(List.of(rfqId)).stream().map(RfqLine::getId).collect(Collectors.toSet());
        Set<Long> seen = new HashSet<>();
        for (QuoteLineReq l : req.lines()) {
            if (!valid.contains(l.rfqLineId())) {
                throw new BizException("报价行不属于询价单 " + r.getRfqNo());
            }
            if (!seen.add(l.rfqLineId())) {
                throw new BizException("同一行不能报两次");
            }
            if (l.validUntil().isBefore(r.getDeadline())) {
                throw new BizException("报价有效期不能早于截止日期 " + r.getDeadline());
            }
            RfqQuote q = quotes.findByRfqLineIdAndSupplierId(l.rfqLineId(), mine).orElseGet(RfqQuote::new);
            q.setRfqId(rfqId);
            q.setRfqLineId(l.rfqLineId());
            q.setSupplierId(mine);
            q.setRoundNo(currentRound);
            q.setUnitPrice(l.unitPrice());
            q.setTaxRate(l.taxRate());
            q.setLeadDays(l.leadDays());
            q.setValidUntil(l.validUntil());
            q.setNote(l.note() == null || l.note().isBlank() ? null : l.note().trim());
            q.setUpdatedBy(currentUser.usernameOrSystem());
            q.setUpdatedAt(time.now());
            quotes.save(q);
        }
        audit.log("供应商报价", r.getRfqNo(), lookup.partner(mine).getName() + " 第 " + currentRound + " 轮报 " + req.lines().size() + " 行");
        return portalViews(mine);
    }

    /** 还没报价的询价数，提醒用。 */
    @Transactional(readOnly = true)
    public List<Rfq> waitingQuote(Long supplierId) {
        List<Long> ids = invited.findBySupplierId(supplierId).stream().map(RfqSupplier::getRfqId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Set<Integer>> quotedRoundsByRfq = quotes.findByRfqIdInAndSupplierId(ids, supplierId).stream()
                .collect(Collectors.groupingBy(RfqQuote::getRfqId, Collectors.mapping(RfqQuote::getRoundNo, Collectors.toSet())));
        return rfqs.findByIdInOrderByIdDesc(ids).stream()
                .filter(r -> sealed(r) && !quotedRoundsByRfq.getOrDefault(r.getId(), Set.of()).contains(r.getRoundNo() <= 0 ? 1 : r.getRoundNo()))
                .toList();
    }

    private List<PortalRfq> portalViews(Long supplierId) {
        List<Long> ids = invited.findBySupplierId(supplierId).stream().map(RfqSupplier::getRfqId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Rfq> list = rfqs.findByIdInOrderByIdDesc(ids);
        Map<Long, List<RfqLine>> ls = lines.findByRfqIdIn(ids).stream().collect(Collectors.groupingBy(RfqLine::getRfqId));
        Map<Long, RfqQuote> mine = quotes.findByRfqIdInAndSupplierId(ids, supplierId).stream()
                .collect(Collectors.toMap(RfqQuote::getRfqLineId, q -> q));
        Map<Long, String> poNos = poNos(mine.values());
        Map<Long, Item> items = lookup.itemMap();
        return list.stream().map(r -> {
            int roundNo = r.getRoundNo() <= 0 ? 1 : r.getRoundNo();
            List<RfqLine> rl = ls.getOrDefault(r.getId(), List.of());
            boolean won = rl.stream().anyMatch(l -> mine.containsKey(l.getId()) && mine.get(l.getId()).isAwarded());
            String state = switch (r.getStatus()) {
                case CANCELLED -> "已取消";
                case AWARDED -> won ? "已中标" : "未中标";
                default -> sealed(r) ? (roundNo > 1 ? "第 " + roundNo + " 轮报价中" : "报价中") : "已截止，等待结果";
            };
            return new PortalRfq(r.getId(), r.getRfqNo(), r.getTitle(), r.getDeadline(), roundNo, r.getRoundNote(), state, sealed(r),
                    rl.stream().map(l -> {
                        Item i = items.get(l.getItemId());
                        RfqQuote q = mine.get(l.getId());
                        return new PortalLine(l.getId(), i.getCode(), i.getName(), i.getUnit(), l.getQty(),
                                q == null ? null : new MyQuote(q.getRoundNo(), q.getPrevUnitPrice(), q.getUnitPrice(), q.getTaxRate(), q.getLeadDays(),
                                        q.getValidUntil(), q.getNote(), q.isAwarded(), poNos.get(q.getPoId())));
                    }).toList());
        }).toList();
    }

    private Map<Long, String> poNos(java.util.Collection<RfqQuote> qs) {
        List<Long> ids = qs.stream().map(RfqQuote::getPoId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, String> out = new HashMap<>();
        orders.findAllById(ids).forEach(p -> out.put(p.getId(), p.getPoNo()));
        return out;
    }

    /** 获取物料最近一次采购订单含税单价，供比价定标时参考。 */
    private Map<Long, BigDecimal> latestPoPriceMap(Set<Long> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, PoLine> latestLineByItem = new HashMap<>();
        for (PoLine line : poLines.findAll()) {
            if (line.getUnitPrice() != null && itemIds.contains(line.getItemId())) {
                PoLine prev = latestLineByItem.get(line.getItemId());
                if (prev == null || (line.getId() != null && prev.getId() != null && line.getId() > prev.getId())) {
                    latestLineByItem.put(line.getItemId(), line);
                }
            }
        }
        Map<Long, BigDecimal> result = new HashMap<>();
        latestLineByItem.forEach((k, v) -> result.put(k, v.getUnitPrice()));
        return result;
    }

    private List<RfqView> views(List<Rfq> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<Long> ids = list.stream().map(Rfq::getId).toList();
        List<RfqLine> allLines = lines.findByRfqIdIn(ids);
        Map<Long, List<RfqLine>> ls = allLines.stream().collect(Collectors.groupingBy(RfqLine::getRfqId));
        Map<Long, BigDecimal> lastPrices = latestPoPriceMap(allLines.stream().map(RfqLine::getItemId).collect(Collectors.toSet()));
        Map<Long, List<RfqSupplier>> ss = invited.findByRfqIdIn(ids).stream().collect(Collectors.groupingBy(RfqSupplier::getRfqId));
        List<RfqQuote> allQuotes = quotes.findByRfqIdIn(ids);
        Map<Long, List<RfqQuote>> byLine = allQuotes.stream().collect(Collectors.groupingBy(RfqQuote::getRfqLineId));
        Map<Long, String> poNos = poNos(allQuotes);
        Map<Long, Item> items = lookup.itemMap();
        Map<Long, Partner> partners = lookup.partnerMap();
        return list.stream().map(r -> {
            int roundNo = r.getRoundNo() <= 0 ? 1 : r.getRoundNo();
            boolean sealed = sealed(r);
            Set<Long> quotedInCurrentRound = allQuotes.stream()
                    .filter(q -> r.getId().equals(q.getRfqId()) && q.getRoundNo() == roundNo)
                    .map(RfqQuote::getSupplierId)
                    .collect(Collectors.toSet());
            List<RfqLineView> lv = ls.getOrDefault(r.getId(), List.of()).stream().map(l -> {
                Item i = items.get(l.getItemId());
                // 开标前不返回价格，不是只在页面上藏起来
                List<RfqQuote> qs = sealed ? List.of() : byLine.getOrDefault(l.getId(), List.of());
                BigDecimal lowest = qs.stream().map(RfqQuote::getUnitPrice).min(Comparator.naturalOrder()).orElse(null);
                return new RfqLineView(l.getId(), i.getCode(), i.getName(), i.getUnit(), l.getQty(),
                        lastPrices.get(l.getItemId()),
                        qs.stream().sorted(Comparator.comparing(RfqQuote::getUnitPrice))
                                .map(q -> new QuoteView(q.getSupplierId(), partners.get(q.getSupplierId()).getName(),
                                        q.getRoundNo(), q.getPrevUnitPrice(),
                                        q.getUnitPrice(), q.getTaxRate(),
                                        q.getUnitPrice().multiply(l.getQty()).setScale(2, RoundingMode.HALF_UP),
                                        q.getLeadDays(), q.getValidUntil(), q.getNote(),
                                        q.getUnitPrice().compareTo(lowest) == 0, q.isAwarded(), poNos.get(q.getPoId())))
                                .toList());
            }).toList();
            List<InvitedView> iv = ss.getOrDefault(r.getId(), List.of()).stream()
                    .map(s -> new InvitedView(s.getSupplierId(), partners.get(s.getSupplierId()).getName(),
                            quotedInCurrentRound.contains(s.getSupplierId())))
                    .toList();
            String label = r.getStatus() == RfqStatus.OPEN && !sealed ? "已截止待定标"
                    : r.getStatus() == RfqStatus.CLOSED ? "已开标待定标"
                    : r.getStatus().getLabel();
            return new RfqView(r.getId(), r.getRfqNo(), r.getTitle(), r.getDeadline(), roundNo, r.getRoundNote(),
                    r.getStatus(), label, sealed, canAward(r), r.getCloseReason(), r.getAwardReason(), r.getAwardedBy(),
                    r.getAwardedAt(), r.getCreatedBy(), r.getCreatedAt(), lv, iv);
        }).toList();
    }
}
