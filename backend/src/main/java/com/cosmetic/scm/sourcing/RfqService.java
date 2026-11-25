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
    public static final int DUE_SOON_DAYS = 3;
    public static final int AWARD_OVERDUE_DAYS = 3;

    private final RfqRepository rfqs;
    private final RfqLineRepository lines;
    private final RfqSupplierRepository invited;
    private final RfqQuoteRepository quotes;
    private final PurchaseService purchase;
    private final SupplierPriceService supplierPrices;
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
                               @NotBlank(message = "请填写本轮议价说明或目标价要求") @Size(max = 300) String note,
                               @Positive Integer roundNo) {
    }

    public record CancelReq(@NotBlank(message = "请填写取消原因") @Size(max = 300) String reason) {
    }

    public record AwardLineReq(@NotNull Long rfqLineId, @NotNull Long supplierId) {
    }

    public record AwardReq(@NotEmpty(message = "至少定标一行") @Valid List<AwardLineReq> lines,
                           @Size(max = 300) String reason,
                           @NotBlank(message = "请输入签批密码") String password,
                           @Positive Integer roundNo,
                           Boolean saveToPriceList) {
        public AwardReq(List<AwardLineReq> lines, String reason, String password, Integer roundNo) {
            this(lines, reason, password, roundNo, Boolean.FALSE);
        }
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

    public record QuoteReq(@NotEmpty(message = "至少报一行") @Valid List<QuoteLineReq> lines,
                           @Positive Integer roundNo) {
    }

    public record QuoteView(Long supplierId, String supplierName, int roundNo, BigDecimal prevUnitPrice,
                            BigDecimal unitPrice, BigDecimal taxRate, BigDecimal amount, int leadDays,
                            LocalDate validUntil, String note, boolean lowest, boolean awarded, String poNo) {
    }

    public record RfqLineView(Long id, String itemCode, String itemName, String unit, BigDecimal qty,
                              BigDecimal lastPoPrice, List<QuoteView> quotes) {
    }

    public record InvitedView(Long supplierId, String supplierName, boolean quoted, long quotedLines) {
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

    private void checkRound(Rfq rfq, Integer expectedRound) {
        if (expectedRound != null && expectedRound != rfq.getRoundNo()) {
            throw new BizException("议价轮次已变更，请刷新页面后重试");
        }
    }

    public record RfqSummary(
            int totalCount, int sealedCount, int waitingAwardCount, int awardedCount,
            int cancelledCount, int multiRoundCount, int closingSoonCount) {
    }

    public record RfqClosureRow(Long id, String rfqNo, String title, int roundNo, LocalDate deadline,
                                long daysLeft, int lineCount, int invitedCount, int quotedCount,
                                Double responseRatePct, boolean sealed, boolean canAward,
                                String riskLevel, String riskLabel, String recommendedAction) {
    }

    public record RfqClosureBoard(int totalOpenCount, int overdueAwardCount, int waitingAwardCount,
                                  int dueSoonCount, int lowResponseCount, List<RfqClosureRow> rows) {
    }

    @Transactional(readOnly = true)
    public RfqSummary summary() {
        LocalDate today = time.today();
        LocalDate soon = today.plusDays(3);
        List<Rfq> all = rfqs.findAll();
        int sealedCount = 0, waitingAwardCount = 0, awardedCount = 0, cancelledCount = 0;
        int multiRoundCount = 0, closingSoonCount = 0;
        for (Rfq r : all) {
            if (r.getRoundNo() > 1) {
                multiRoundCount++;
            }
            if (r.getStatus() == RfqStatus.AWARDED) {
                awardedCount++;
            } else if (r.getStatus() == RfqStatus.CANCELLED) {
                cancelledCount++;
            } else if (sealed(r)) {
                sealedCount++;
                if (r.getDeadline() != null && !r.getDeadline().isAfter(soon)) {
                    closingSoonCount++;
                }
            } else if (canAward(r)) {
                waitingAwardCount++;
            }
        }
        return new RfqSummary(all.size(), sealedCount, waitingAwardCount, awardedCount,
                cancelledCount, multiRoundCount, closingSoonCount);
    }

    /** 询价比价截止倒计时与超期未定标监控看板（V75），仅限有价格权限的采购/财务/管理员访问。 */
    @Transactional(readOnly = true)
    public RfqClosureBoard closureBoard() {
        LocalDate today = time.today();
        List<RfqView> all = listAll();
        List<RfqClosureRow> rows = new ArrayList<>();
        int overdueAward = 0;
        int waitingAward = 0;
        int dueSoon = 0;
        int lowResp = 0;

        for (RfqView r : all) {
            if (r.status() == RfqStatus.AWARDED || r.status() == RfqStatus.CANCELLED) {
                continue;
            }
            long daysLeft = r.deadline() == null ? 0L
                    : java.time.temporal.ChronoUnit.DAYS.between(today, r.deadline());
            int invitedCnt = r.suppliers().size();
            int quotedCnt = (int) r.suppliers().stream().filter(InvitedView::quoted).count();
            Double respRate = invitedCnt > 0 ? Math.round(quotedCnt * 1000.0 / invitedCnt) / 10.0 : null;
            int minExpected = Math.min(MIN_QUOTES, invitedCnt);

            String riskLevel;
            String riskLabel;
            String action;
            if (r.canAward()) {
                waitingAward++;
                if (daysLeft < -AWARD_OVERDUE_DAYS) {
                    overdueAward++;
                    riskLevel = "OVERDUE";
                    riskLabel = "超期未定标(" + (-daysLeft) + "天)";
                    action = "报价已截止 " + (-daysLeft) + " 天仍未定标（响应 " + quotedCnt + "/" + invitedCnt + " 家），请尽快完成比价签批或发起下一轮议价";
                } else {
                    riskLevel = "WAITING_AWARD";
                    riskLabel = "待比价定标";
                    action = "已具备开标比价条件（响应 " + quotedCnt + "/" + invitedCnt + " 家），请尽快比价定标生成采购单";
                }
            } else if (daysLeft <= DUE_SOON_DAYS) {
                dueSoon++;
                boolean lackQuotes = quotedCnt < minExpected;
                if (lackQuotes) {
                    lowResp++;
                }
                riskLevel = "DUE_SOON";
                riskLabel = daysLeft == 0
                        ? (lackQuotes ? "今日截止·响应不足" : "今日截止")
                        : (lackQuotes ? "3天内截止·响应不足" : "3天内截止");
                action = lackQuotes
                        ? "距报价截止仅 " + daysLeft + " 天，当前仅 " + quotedCnt + "/" + invitedCnt + " 家报价，请立即催促未报价供应商"
                        : "距报价截止仅 " + daysLeft + " 天（已报 " + quotedCnt + "/" + invitedCnt + " 家），可准备开标比价";
            } else if (quotedCnt == 0 && invitedCnt > 0) {
                lowResp++;
                riskLevel = "LOW_RESPONSE";
                riskLabel = "暂无报价";
                action = "已邀请 " + invitedCnt + " 家供应商但尚无报价，请跟进供应商门户响应情况";
            } else {
                riskLevel = "OPEN";
                riskLabel = "密封询价中";
                action = "距报价截止还有 " + daysLeft + " 天，已报 " + quotedCnt + "/" + invitedCnt + " 家";
            }

            rows.add(new RfqClosureRow(
                    r.id(), r.rfqNo(), r.title(), r.roundNo(), r.deadline(), daysLeft,
                    r.lines().size(), invitedCnt, quotedCnt, respRate, r.sealed(), r.canAward(),
                    riskLevel, riskLabel, action));
        }

        rows.sort(Comparator.comparingInt((RfqClosureRow x) -> closureRiskRank(x.riskLevel()))
                .thenComparingLong(RfqClosureRow::daysLeft)
                .thenComparing(RfqClosureRow::rfqNo));

        return new RfqClosureBoard(rows.size(), overdueAward, waitingAward, dueSoon, lowResp, rows);
    }

    private static int closureRiskRank(String level) {
        return switch (level) {
            case "OVERDUE" -> 0;
            case "WAITING_AWARD" -> 1;
            case "DUE_SOON" -> 2;
            case "LOW_RESPONSE" -> 3;
            default -> 4;
        };
    }

    @Transactional(readOnly = true)
    public List<RfqView> list() {
        return views(rfqs.findTop300ByOrderByIdDesc());
    }

    @Transactional(readOnly = true)
    public List<RfqView> listAll() {
        return views(rfqs.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id")));
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
        int currentRound = r.getRoundNo();
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
     * 已开标或截止后可进入下一轮。此前报价保留原有效期，供供应商参照。
     */
    @Transactional
    public RfqView nextRound(Long id, NextRoundReq req) {
        Rfq r = rfqs.lockById(id).orElseThrow(() -> BizException.notFound("询价单"));
        checkRound(r, req.roundNo());
        if (!canAward(r)) {
            throw new BizException(sealed(r) ? "当前轮次尚未开标或截止，不能发起下一轮议价"
                    : "询价单 " + r.getRfqNo() + " 当前为" + r.getStatus().getLabel() + "，不能发起下一轮议价");
        }
        LocalDate today = time.today();
        if (req.deadline().isBefore(today)) {
            throw new BizException("新报价截止日期不能早于今天");
        }
        int nextRoundNo = r.getRoundNo() + 1;
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
        checkRound(r, req.roundNo());
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
        Map<Long, List<RfqQuote>> byLine = quotes.findByRfqIdIn(List.of(id)).stream()
                .filter(q -> q.getRoundNo() == r.getRoundNo())
                .collect(Collectors.groupingBy(RfqQuote::getRfqLineId));
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
                    .orElseThrow(() -> new BizException("所选供应商本轮未提交 " + items.get(l.getItemId()).getName() + " 的报价"));
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
                if (Boolean.TRUE.equals(req.saveToPriceList())) {
                    RfqLine rl = lineMap.get(q.getRfqLineId());
                    supplierPrices.create(new SupplierPriceService.PriceReq(
                            e.getKey(), rl.getItemId(), q.getUnitPrice(), q.getTaxRate(),
                            today, q.getValidUntil(), "询价定标沉淀：" + r.getRfqNo()));
                }
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
        checkRound(r, req.roundNo());
        if (!sealed(r)) {
            throw new BizException("询价单 " + r.getRfqNo() + " 已截止或已结束，不能再报价");
        }
        int currentRound = r.getRoundNo();
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
            if (q.getRoundNo() < currentRound) {
                q.setPrevUnitPrice(q.getUnitPrice());
            }
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
                .filter(r -> sealed(r) && !quotedRoundsByRfq.getOrDefault(r.getId(), Set.of()).contains(r.getRoundNo()))
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
            int roundNo = r.getRoundNo();
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
        Map<Long, BigDecimal> result = new HashMap<>();
        for (PoLine line : poLines.latestPricedByItemIdIn(itemIds)) {
            result.put(line.getItemId(), line.getUnitPrice());
        }
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
            int roundNo = r.getRoundNo();
            boolean sealed = sealed(r);
            boolean showQuotes = canAward(r) || r.getStatus() == RfqStatus.AWARDED;
            List<RfqLine> rfqLines = ls.getOrDefault(r.getId(), List.of());
            Map<Long, Long> quotedLinesBySupplier = allQuotes.stream()
                    .filter(q -> r.getId().equals(q.getRfqId()) && q.getRoundNo() == roundNo)
                    .collect(Collectors.groupingBy(RfqQuote::getSupplierId, Collectors.counting()));
            List<RfqLineView> lv = rfqLines.stream().map(l -> {
                Item i = items.get(l.getItemId());
                // 开标前不返回价格，不是只在页面上藏起来
                List<RfqQuote> qs = showQuotes ? byLine.getOrDefault(l.getId(), List.of()).stream()
                        // 已定标的历史单据可能引用 V28 允许使用的旧轮次报价。
                        .filter(q -> q.getRoundNo() == roundNo || q.isAwarded()).toList() : List.of();
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
                    .map(s -> {
                        long count = quotedLinesBySupplier.getOrDefault(s.getSupplierId(), 0L);
                        return new InvitedView(s.getSupplierId(), partners.get(s.getSupplierId()).getName(),
                                count > 0, count);
                    })
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
