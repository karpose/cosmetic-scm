package com.cosmetic.scm.sales;

import com.cosmetic.scm.common.Qty;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.DocNoService;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.TxnType;
import com.cosmetic.scm.domain.WarehouseKind;
import com.cosmetic.scm.domain.rules.FefoAllocator;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.inventory.InventoryService;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.Warehouse;
import com.cosmetic.scm.security.CurrentUser;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 销售出库：只分配已上市放行的成品批次，按先到期先出排序，
 * 并排除剩余效期达不到客户接收标准的批次。批号随出库单记录到客户，用于召回。
 */
@Service
@RequiredArgsConstructor
public class SalesService {

    private final SalesOrderRepository orders;
    private final SoLineRepository lines;
    private final SoAllocationRepository allocations;
    private final BatchRepository batches;
    private final InventoryService inventory;
    private final MasterLookup lookup;
    private final com.cosmetic.scm.master.PartnerRepository partners;
    private final CustomerPriceService customerPrices;
    private final CustomerPriceRepository customerPriceRepo;
    private final com.cosmetic.scm.settlement.CustomerStatementRepository customerStatements;
    private final com.cosmetic.scm.settlement.CustomerStatementLineRepository customerStatementLines;
    private final DocNoService docNo;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;
    private final SalesRecordService salesRecords;

    private static final Set<String> SALES_PRICE_ROLES = Set.of("ROLE_ADMIN", "ROLE_SALES", "ROLE_FINANCE");

    /** 缺单价页面一次最多列出的行数；行数合计按全部算。 */
    public static final int UNPRICED_ROWS = 300;

    public record SoLineReq(@NotNull Long itemId, @NotNull @Positive @Qty BigDecimal qty,
                            @Positive(message = "含税单价要大于 0") @Digits(integer = 14, fraction = 4) BigDecimal unitPrice,
                            @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal taxRate) {
        public SoLineReq(Long itemId, BigDecimal qty) {
            this(itemId, qty, null, null);
        }
    }

    public record SoReq(@NotNull Long customerId, @NotNull Long warehouseId,
                        @NotEmpty(message = "至少一个品项") @Valid List<SoLineReq> lines) {
    }

    public record PickView(Long batchId, String batchNo, Long warehouseId, BigDecimal qty, LocalDate expDate) {
    }

    public record AllocLineView(Long itemId, String itemCode, String itemName, String unit, BigDecimal qty,
                                List<PickView> picks, BigDecimal shortQty, BigDecimal excludedQty) {
    }

    public record PreviewView(String customerName, int minShelfPct, boolean fulfilled, List<AllocLineView> lines) {
    }

    /** billed：这一行是否已进没作废的应收对账单（进了就不能再改单价）；只在页面列表、有价格权限时给出，其余为空。 */
    public record SoLineView(Long lineId, Long itemId, String itemCode, String itemName, BigDecimal qty,
                             BigDecimal unitPrice, BigDecimal taxRate, BigDecimal amount, Boolean billed) {
    }

    /** 补单价：unitPrice 可以是 0（赠品、样品，不收款），这时要写原因。taxRate 留空沿用这一行原来的税率，原来没有的取客户默认。 */
    public record LinePriceItem(@NotNull(message = "请选择出库行") Long soLineId,
                                @NotNull(message = "请填写含税单价") @DecimalMin(value = "0", message = "含税单价不能小于 0")
                                @Digits(integer = 14, fraction = 4) BigDecimal unitPrice,
                                @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal taxRate) {
    }

    /** reason：改已有单价、按 0 元定价时必填。 */
    public record LinePriceReq(@NotEmpty(message = "至少填一行单价") @Size(max = 500, message = "一次最多填 500 行")
                               @Valid List<LinePriceItem> lines,
                               @Size(max = 300) String reason) {
    }

    /** 补价以后这个客户的额度占用（未结应收 + 已出库未对账），unpricedLeft 是还没单价的行数。 */
    public record LinePriceResult(Long customerId, String customerName, int updated, BigDecimal amount,
                                  BigDecimal creditLimit, BigDecimal creditUsed, boolean overLimit, long unpricedLeft) {
    }

    /**
     * 缺单价的出库行。suggestedPrice 是建议价（出库日有效的协议价 → 这个客户这个成品最近一次单价 → 现行协议价），
     * 只是预填参考，保存时以页面上填的为准。
     */
    public record UnpricedLineView(Long lineId, Long soId, String soNo, LocalDate orderDate,
                                   Long customerId, String customerCode, String customerName, String channel,
                                   Long itemId, String itemCode, String itemName, String unit, BigDecimal qty,
                                   BigDecimal defaultTaxRate, BigDecimal suggestedPrice, BigDecimal suggestedTaxRate,
                                   String suggestedSource) {
    }

    public record UnpricedCustomer(Long customerId, String customerCode, String customerName, long lines, LocalDate oldest) {
    }

    /** total：按筛选条件的全部行数；customers：所有有缺单价行的客户（不受筛选影响）；rows 最多 UNPRICED_ROWS 行。 */
    public record UnpricedView(long total, int cap, List<UnpricedCustomer> customers, List<UnpricedLineView> rows) {
    }

    public record AllocView(Long batchId, String batchNo, Long itemId, String itemName, BigDecimal qty) {
    }

    public record SoView(Long id, String soNo, Long customerId, String customerName, String channel,
                         Long warehouseId, String warehouseName, LocalDate orderDate, String status,
                         String createdBy, LocalDateTime createdAt, List<SoLineView> lines,
                         List<AllocView> allocations, List<String> recordGaps) {
    }

    private record Plan(Partner customer, Warehouse warehouse, List<Item> items, List<SoLineReq> lines,
                        List<FefoAllocator.Result> results) {
        boolean fulfilled() {
            return results.stream().allMatch(FefoAllocator.Result::fulfilled);
        }
    }

    public record ChannelStat(String channel, int orderCount, BigDecimal totalQty, BigDecimal totalAmount) {
    }

    public record SalesSummary(
            int windowDays, LocalDate from, LocalDate to,
            int totalOrdersAll, int ordersInWindow, BigDecimal unitsInWindow,
            int activeCustomersInWindow, BigDecimal amountInWindow,
            List<ChannelStat> byChannel) {
    }

    @Transactional(readOnly = true)
    public SalesSummary summary(int days) {
        int windowDays = Math.max(1, Math.min(365, days));
        LocalDate to = time.today();
        LocalDate from = to.minusDays(windowDays - 1L);
        int totalOrdersAll = (int) orders.count();
        List<SalesOrder> inWindow = orders.findByOrderDateGreaterThanEqual(from);
        boolean showPrice = salesPriceAllowed();
        if (inWindow.isEmpty()) {
            return new SalesSummary(windowDays, from, to, totalOrdersAll, 0, BigDecimal.ZERO, 0,
                    showPrice ? BigDecimal.ZERO : null, List.of());
        }
        List<Long> ids = inWindow.stream().map(SalesOrder::getId).toList();
        Map<Long, List<SoLine>> linesBySo = lines.findBySoIdIn(ids).stream()
                .collect(Collectors.groupingBy(SoLine::getSoId));
        Map<Long, Partner> pm = lookup.partnerMap();
        Set<Long> customers = new HashSet<>();
        BigDecimal totalUnits = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        Map<String, int[]> chOrders = new java.util.LinkedHashMap<>();
        Map<String, BigDecimal> chQty = new java.util.LinkedHashMap<>();
        Map<String, BigDecimal> chAmt = new java.util.LinkedHashMap<>();
        for (SalesOrder so : inWindow) {
            customers.add(so.getCustomerId());
            Partner c = pm.get(so.getCustomerId());
            String channel = c != null && c.getChannel() != null && !c.getChannel().isBlank() ? c.getChannel().trim() : "未分渠道";
            chOrders.computeIfAbsent(channel, k -> new int[1])[0]++;
            for (SoLine l : linesBySo.getOrDefault(so.getId(), List.of())) {
                totalUnits = totalUnits.add(l.getQty());
                chQty.merge(channel, l.getQty(), BigDecimal::add);
                if (showPrice && l.getUnitPrice() != null) {
                    BigDecimal amt = l.getQty().multiply(l.getUnitPrice()).setScale(2, RoundingMode.HALF_UP);
                    totalAmount = totalAmount.add(amt);
                    chAmt.merge(channel, amt, BigDecimal::add);
                }
            }
        }
        List<ChannelStat> byChannel = chOrders.keySet().stream()
                .map(ch -> new ChannelStat(
                        ch,
                        chOrders.get(ch)[0],
                        chQty.getOrDefault(ch, BigDecimal.ZERO),
                        showPrice ? chAmt.getOrDefault(ch, BigDecimal.ZERO) : null))
                .sorted((a, b) -> b.totalQty().compareTo(a.totalQty()))
                .toList();
        return new SalesSummary(windowDays, from, to, totalOrdersAll, inWindow.size(), totalUnits,
                customers.size(), showPrice ? totalAmount : null, byChannel);
    }

    @Transactional(readOnly = true)
    public List<SoView> list() {
        return views(orders.findTop300ByOrderByIdDesc(), true);
    }

    /** 全部出库单，导出用（页面列表只取最近 300 张）。 */
    @Transactional(readOnly = true)
    public List<SoView> listAll() {
        return views(orders.findAllByOrderByIdDesc(), false);
    }

    /** withGaps：同时算销售记录缺的内容（页面列表用；导出全部出库单时不算）。 */
    private List<SoView> views(List<SalesOrder> list, boolean withGaps) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, List<String>> gaps = withGaps ? salesRecords.gapsByOrder(list) : Map.of();
        List<Long> ids = list.stream().map(SalesOrder::getId).toList();
        List<SoLine> allLines = lines.findBySoIdIn(ids);
        Map<Long, List<SoLine>> lm = allLines.stream().collect(Collectors.groupingBy(SoLine::getSoId));
        List<SoAllocation> allocs = allocations.findBySoIdIn(ids);
        Map<Long, List<SoAllocation>> am = allocs.stream().collect(Collectors.groupingBy(SoAllocation::getSoId));
        Map<Long, Batch> bm = batches.findAllById(allocs.stream().map(SoAllocation::getBatchId).distinct().toList())
                .stream().collect(Collectors.toMap(Batch::getId, Function.identity()));
        Map<Long, Item> im = lookup.itemMap();
        Map<Long, Partner> pm = lookup.partnerMap();
        Map<Long, Warehouse> wm = lookup.warehouseMap();
        boolean showPrice = salesPriceAllowed();
        Set<Long> billed = withGaps && showPrice && !allLines.isEmpty()
                ? Set.copyOf(customerStatementLines.billedSoLineIds(allLines.stream().map(SoLine::getId).toList())) : null;
        return list.stream().map(so -> {
            Partner c = pm.get(so.getCustomerId());
            Warehouse w = wm.get(so.getWarehouseId());
            List<SoLineView> lv = lm.getOrDefault(so.getId(), List.of()).stream()
                    .map(l -> {
                        BigDecimal up = showPrice ? l.getUnitPrice() : null;
                        BigDecimal tr = showPrice ? l.getTaxRate() : null;
                        BigDecimal amt = up != null ? l.getQty().multiply(up).setScale(2, RoundingMode.HALF_UP) : null;
                        return new SoLineView(l.getId(), l.getItemId(), im.get(l.getItemId()).getCode(), im.get(l.getItemId()).getName(),
                                l.getQty(), up, tr, amt, billed == null ? null : billed.contains(l.getId()));
                    })
                    .toList();
            List<AllocView> av = am.getOrDefault(so.getId(), List.of()).stream()
                    .map(a -> new AllocView(a.getBatchId(), bm.get(a.getBatchId()).getBatchNo(), a.getItemId(),
                            im.get(a.getItemId()).getName(), a.getQty()))
                    .toList();
            return new SoView(so.getId(), so.getSoNo(), so.getCustomerId(), c.getName(), c.getChannel(),
                    so.getWarehouseId(), w.getName(), so.getOrderDate(), so.getStatus(), so.getCreatedBy(),
                    so.getCreatedAt(), lv, av, gaps.getOrDefault(so.getId(), List.of()));
        }).toList();
    }

    /** 只计算分配结果，不扣库存，供页面预览。 */
    @Transactional(readOnly = true)
    public PreviewView preview(SoReq req) {
        return toPreview(plan(req, false));
    }

    /**
     * 出库前锁客户行：信用额度按“未结对账单 + 已出库未对账 + 本次出库”算，没有单据可锁，所以锁客户，
     * 同一客户的出库排队，几张单同时出库不会一起超额。必须在事务里任何普通查询之前调用
     * （MySQL 可重复读下，之前有过普通查询就读不到别人刚提交的出库）；经销商订单审核、电商订单出库在锁住自己的订单后先调它。
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void lockCustomer(Long customerId) {
        partners.lockById(customerId).orElseThrow(() -> BizException.notFound("客户"));
    }

    @Transactional
    public SoView create(SoReq req) {
        lockCustomer(req.customerId());
        Plan plan = plan(req, true);
        if (!plan.fulfilled()) {
            List<String> shortages = new ArrayList<>();
            for (int i = 0; i < plan.items().size(); i++) {
                FefoAllocator.Result r = plan.results().get(i);
                if (!r.fulfilled()) {
                    shortages.add(plan.items().get(i).getName() + " 缺 " + plain(r.shortQty())
                            + (r.excludedQty().signum() > 0 ? "（另有 " + plain(r.excludedQty()) + " 因效期不达标被排除）" : ""));
                }
            }
            throw new BizException("可分配库存不足：" + String.join("；", shortages));
        }
        LocalDate today = time.today();
        BigDecimal defaultRate = plan.customer().getDefaultTaxRate() != null
                ? plan.customer().getDefaultTaxRate() : new BigDecimal("13.00");
        boolean canPrice = salesPriceAllowed();
        BigDecimal[] resolvedPrices = new BigDecimal[plan.items().size()];
        BigDecimal[] resolvedRates = new BigDecimal[plan.items().size()];
        BigDecimal orderTotal = BigDecimal.ZERO;
        for (int i = 0; i < plan.items().size(); i++) {
            Item item = plan.items().get(i);
            SoLineReq lr = plan.lines().get(i);
            if (lr.unitPrice() != null) {
                if (!canPrice) {
                    throw new BizException(HttpStatus.FORBIDDEN, "没有权限录入销售单价");
                }
                resolvedPrices[i] = lr.unitPrice();
                resolvedRates[i] = lr.taxRate() != null ? lr.taxRate() : defaultRate;
            } else {
                var cp = customerPrices.effectivePrice(plan.customer().getId(), item.getId(), today).orElse(null);
                if (cp != null) {
                    resolvedPrices[i] = cp.getUnitPrice();
                    resolvedRates[i] = cp.getTaxRate() != null ? cp.getTaxRate() : defaultRate;
                }
            }
            if (resolvedPrices[i] != null) {
                orderTotal = orderTotal.add(lr.qty().multiply(resolvedPrices[i]).setScale(2, RoundingMode.HALF_UP));
            }
        }
        checkCreditLimit(plan.customer(), orderTotal);

        SalesOrder so = new SalesOrder();
        so.setSoNo(docNo.next("SO", today));
        so.setCustomerId(plan.customer().getId());
        so.setWarehouseId(plan.warehouse().getId());
        so.setOrderDate(today);
        so.setStatus("SHIPPED");
        so.setCreatedBy(currentUser.usernameOrSystem());
        so.setCreatedAt(time.now());
        // 销售记录：记下出库时的购买者信息，以后改主数据不影响这条记录
        so.setBuyerName(plan.customer().getName());
        so.setBuyerAddress(blankToNull(plan.customer().getAddress()));
        so.setBuyerContact(blankToNull(plan.customer().getContact()));
        orders.save(so);

        List<String> detail = new ArrayList<>();
        for (int i = 0; i < plan.items().size(); i++) {
            Item item = plan.items().get(i);
            SoLineReq lr = plan.lines().get(i);
            SoLine line = new SoLine();
            line.setSoId(so.getId());
            line.setItemId(item.getId());
            line.setQty(lr.qty());
            line.setUnitPrice(resolvedPrices[i]);
            line.setTaxRate(resolvedRates[i]);
            line.setFilingNo(blankToNull(item.getFilingNo()));
            line.setNetContent(SalesRecordService.netContentOf(item));
            lines.save(line);
            List<FefoAllocator.Pick> picks = plan.results().get(i).picks();
            inventory.consume(picks, TxnType.SHIP, so.getSoNo());
            for (FefoAllocator.Pick p : picks) {
                SoAllocation a = new SoAllocation();
                a.setSoId(so.getId());
                a.setItemId(item.getId());
                a.setBatchId(p.batchId());
                a.setWarehouseId(p.warehouseId());
                a.setQty(p.qty());
                allocations.save(a);
                detail.add(p.batchNo() + "×" + plain(p.qty()));
            }
        }
        audit.log("销售出库", so.getSoNo(), plan.customer().getName() + "：" + String.join("，", detail));
        return list().stream().filter(v -> v.id().equals(so.getId())).findFirst()
                .orElseThrow(() -> BizException.notFound("出库单"));
    }

    private void checkCreditLimit(Partner customer, BigDecimal orderTotal) {
        if (customer.getCreditLimit() == null || orderTotal.signum() <= 0) {
            return;
        }
        BigDecimal[] usage = creditUsage(customer.getId());
        BigDecimal openAr = usage[0].add(usage[1]);
        if (openAr.add(orderTotal).compareTo(customer.getCreditLimit()) > 0) {
            long unpriced = lines.countUnpricedUnbilled(customer.getId(), null);
            throw new BizException("客户 " + customer.getName() + " 信用额度不足：授信 ¥" + customer.getCreditLimit().toPlainString()
                    + "，当前未结应收 ¥" + openAr.toPlainString() + "（其中已出库未对账 ¥" + usage[1].toPlainString() + "）"
                    + "，本次出库 ¥" + orderTotal.toPlainString() + "，请先回款核销或调整授信额度"
                    + (unpriced > 0 ? "。另有 " + unpriced + " 行出库还没有单价，没算进去" : ""));
        }
    }

    /** 额度占用：[未结应收对账单待收, 已出库未对账的有价出库行]，和授信概览同一口径。 */
    private BigDecimal[] creditUsage(Long customerId) {
        BigDecimal billed = customerStatements.findByCustomerIdOrderByIdDesc(customerId).stream()
                .filter(s -> !"PAID".equals(s.getStatus()) && !"CANCELLED".equals(s.getStatus()))
                .map(com.cosmetic.scm.settlement.CustomerStatement::outstandingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal unbilled = lines.unbilledShippedAmount(customerId);
        return new BigDecimal[] {billed, unbilled == null ? BigDecimal.ZERO : unbilled};
    }

    /** 已出库、没有单价、没进应收对账单的行，补单价用。只给有销售价格权限的角色。 */
    @Transactional(readOnly = true)
    public UnpricedView unpriced(Long customerId, LocalDate to) {
        requirePriceRole();
        Map<Long, Partner> pm = lookup.partnerMap();
        List<UnpricedCustomer> customers = lines.countUnpricedUnbilledByCustomer().stream()
                .map(r -> {
                    Partner c = pm.get((Long) r[0]);
                    return new UnpricedCustomer((Long) r[0], c == null ? null : c.getCode(), c == null ? "（已删除）" : c.getName(),
                            ((Number) r[1]).longValue(), (LocalDate) r[2]);
                })
                .sorted(java.util.Comparator.comparingLong(UnpricedCustomer::lines).reversed()
                        .thenComparing(UnpricedCustomer::customerCode, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();
        long total = lines.countUnpricedUnbilled(customerId, to);
        if (total == 0) {
            return new UnpricedView(0, UNPRICED_ROWS, customers, List.of());
        }
        List<SoLine> rows = lines.unpricedUnbilled(customerId, to, PageRequest.of(0, UNPRICED_ROWS));
        Map<Long, SalesOrder> om = orders.findAllById(rows.stream().map(SoLine::getSoId).distinct().toList()).stream()
                .collect(Collectors.toMap(SalesOrder::getId, Function.identity()));
        Set<Long> cids = om.values().stream().map(SalesOrder::getCustomerId).collect(Collectors.toSet());
        Set<Long> iids = rows.stream().map(SoLine::getItemId).collect(Collectors.toSet());
        // 协议价：一次取这些客户启用中的，按“生效日期新的在前”分到客户 + 成品
        Map<String, List<CustomerPrice>> agreed = new HashMap<>();
        for (CustomerPrice cp : customerPriceRepo.findByCustomerIdInAndActiveTrueOrderByValidFromDescIdDesc(cids)) {
            agreed.computeIfAbsent(cp.getCustomerId() + ":" + cp.getItemId(), k -> new ArrayList<>()).add(cp);
        }
        // 这个客户这个成品最近一次有价的出库行
        List<SoLine> lastLines = lines.findAllById(lines.latestPricedLineIds(cids, iids));
        Map<Long, SalesOrder> lastOrders = orders.findAllById(lastLines.stream().map(SoLine::getSoId).distinct().toList()).stream()
                .collect(Collectors.toMap(SalesOrder::getId, Function.identity()));
        Map<String, SoLine> lastByKey = new HashMap<>();
        for (SoLine l : lastLines) {
            lastByKey.put(lastOrders.get(l.getSoId()).getCustomerId() + ":" + l.getItemId(), l);
        }
        Map<Long, Item> im = lookup.itemMap();
        LocalDate today = time.today();
        List<UnpricedLineView> out = new ArrayList<>();
        for (SoLine l : rows) {
            SalesOrder so = om.get(l.getSoId());
            Partner c = pm.get(so.getCustomerId());
            Item item = im.get(l.getItemId());
            BigDecimal defRate = l.getTaxRate() != null ? l.getTaxRate()
                    : c != null && c.getDefaultTaxRate() != null ? c.getDefaultTaxRate() : new BigDecimal("13.00");
            String key = so.getCustomerId() + ":" + l.getItemId();
            List<CustomerPrice> cps = agreed.getOrDefault(key, List.of());
            CustomerPrice atOrder = cps.stream().filter(p -> p.isEffectiveOn(so.getOrderDate())).findFirst().orElse(null);
            SoLine last = lastByKey.get(key);
            CustomerPrice now = cps.stream().filter(p -> p.isEffectiveOn(today)).findFirst().orElse(null);
            BigDecimal price = null;
            BigDecimal rate = null;
            String source = null;
            if (atOrder != null) {
                price = atOrder.getUnitPrice();
                rate = atOrder.getTaxRate();
                source = "出库日有效的协议价";
            } else if (last != null) {
                price = last.getUnitPrice();
                rate = last.getTaxRate();
                source = "最近一次单价（" + lastOrders.get(last.getSoId()).getSoNo() + "）";
            } else if (now != null) {
                price = now.getUnitPrice();
                rate = now.getTaxRate();
                source = "现行协议价";
            }
            out.add(new UnpricedLineView(l.getId(), so.getId(), so.getSoNo(), so.getOrderDate(),
                    so.getCustomerId(), c == null ? null : c.getCode(), c == null ? "（已删除）" : c.getName(), c == null ? null : c.getChannel(),
                    l.getItemId(), item.getCode(), item.getName(), item.getUnit(), l.getQty(),
                    defRate, price, price == null ? null : (rate != null ? rate : defRate), source));
        }
        return new UnpricedView(total, UNPRICED_ROWS, customers, out);
    }

    /**
     * 补录或更正出库行单价（含税）。补上以后这一行进应收对账、占信用额度、销售记录按它显示价格。
     * 已进没作废应收对账单的行不能改（要改先由财务作废那张对账单）。改已有单价、按 0 元定价要写原因。
     * 可结算的出库行和信用额度都是算出来的，所以和生成应收对账单、出库一样先锁客户行，而且是事务第一句。
     * 补价后超出授信也照样保存（货已经发出去，应收要记上），返回超额提示，之后的出库会被额度校验拦下。
     */
    @Transactional
    public LinePriceResult setLinePrices(Long customerId, LinePriceReq req) {
        requirePriceRole();
        Partner customer = partners.lockById(customerId).orElseThrow(() -> BizException.notFound("客户"));
        if (customer.getKind() != PartnerKind.CUSTOMER) {
            throw new BizException(customer.getName() + " 不是客户");
        }
        Map<Long, LinePriceItem> byId = new LinkedHashMap<>();
        for (LinePriceItem it : req.lines()) {
            if (byId.put(it.soLineId(), it) != null) {
                throw new BizException("同一出库行填了两次，请只保留一个单价");
            }
        }
        Map<Long, SoLine> lineMap = lines.findAllById(byId.keySet()).stream()
                .collect(Collectors.toMap(SoLine::getId, Function.identity()));
        Map<Long, SalesOrder> om = orders.findAllById(lineMap.values().stream().map(SoLine::getSoId).distinct().toList()).stream()
                .collect(Collectors.toMap(SalesOrder::getId, Function.identity()));
        for (Long id : byId.keySet()) {
            SoLine l = lineMap.get(id);
            // 别的客户的出库行一律当作不存在
            if (l == null || !customer.getId().equals(om.get(l.getSoId()).getCustomerId())) {
                throw new BizException(HttpStatus.NOT_FOUND, "出库行不存在或不是 " + customer.getName() + " 的，请刷新后重试");
            }
        }
        Map<Long, Item> im = lookup.itemMap();
        List<String> billedRefs = new ArrayList<>();
        for (Object[] r : customerStatementLines.activeSoLineStatementRefs(byId.keySet())) {
            SoLine l = lineMap.get((Long) r[0]);
            billedRefs.add(om.get(l.getSoId()).getSoNo() + " 的 " + im.get(l.getItemId()).getName() + "（" + r[1] + "）");
        }
        if (!billedRefs.isEmpty()) {
            throw new BizException("这些出库行已进应收对账单，不能再改单价：" + String.join("、", billedRefs.stream().limit(5).toList())
                    + (billedRefs.size() > 5 ? " 等 " + billedRefs.size() + " 行" : "") + "。要更正请财务先作废对账单，改好后重新生成");
        }
        BigDecimal defaultRate = customer.getDefaultTaxRate() != null ? customer.getDefaultTaxRate() : new BigDecimal("13.00");
        String reason = blankToNull(req.reason());
        boolean needReason = false;
        int updated = 0;
        BigDecimal amount = BigDecimal.ZERO;
        Map<Long, List<String>> detailBySo = new LinkedHashMap<>();
        Set<Long> correctedSo = new HashSet<>();
        for (LinePriceItem it : byId.values()) {
            SoLine l = lineMap.get(it.soLineId());
            BigDecimal oldPrice = l.getUnitPrice();
            BigDecimal oldRate = l.getTaxRate();
            BigDecimal newRate = it.taxRate() != null ? it.taxRate() : oldRate != null ? oldRate : defaultRate;
            if (oldPrice != null && oldPrice.compareTo(it.unitPrice()) == 0 && oldRate != null && oldRate.compareTo(newRate) == 0) {
                continue;
            }
            if (oldPrice != null) {
                needReason = true;
                correctedSo.add(l.getSoId());
            }
            if (it.unitPrice().signum() == 0) {
                needReason = true;
            }
            l.setUnitPrice(it.unitPrice());
            l.setTaxRate(newRate);
            lines.save(l);
            updated++;
            amount = amount.add(l.getQty().multiply(it.unitPrice()).setScale(2, RoundingMode.HALF_UP));
            detailBySo.computeIfAbsent(l.getSoId(), k -> new ArrayList<>()).add(im.get(l.getItemId()).getName() + " × " + plain(l.getQty()) + "："
                    + (oldPrice == null ? "未定价" : "¥" + plain(oldPrice) + "（税率 " + plain(oldRate == null ? defaultRate : oldRate) + "%）")
                    + " → ¥" + plain(it.unitPrice()) + "（税率 " + plain(newRate) + "%）");
        }
        if (updated == 0) {
            throw new BizException("单价和税率都和原来一样，没有要保存的");
        }
        if (needReason && reason == null) {
            throw new BizException("改已有单价或按 0 元定价（赠品、样品）要写明原因");
        }
        for (Map.Entry<Long, List<String>> e : detailBySo.entrySet()) {
            audit.logMoney(correctedSo.contains(e.getKey()) ? "更正出库单价" : "补录出库单价", om.get(e.getKey()).getSoNo(),
                    customer.getName() + "：" + String.join("；", e.getValue()), reason);
        }
        BigDecimal[] usage = creditUsage(customer.getId());
        BigDecimal used = usage[0].add(usage[1]);
        boolean over = customer.getCreditLimit() != null && used.compareTo(customer.getCreditLimit()) > 0;
        return new LinePriceResult(customer.getId(), customer.getName(), updated, amount, customer.getCreditLimit(), used, over,
                lines.countUnpricedUnbilled(customer.getId(), null));
    }

    private static void requirePriceRole() {
        if (!salesPriceAllowed()) {
            throw new BizException(HttpStatus.FORBIDDEN, "销售单价只有销售、财务和管理员能看和改");
        }
    }

    private static boolean salesPriceAllowed() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> SALES_PRICE_ROLES.contains(a.getAuthority()));
    }

    /** forUpdate：真正出库时锁库存行再分配；预览只读，不加锁。 */
    private Plan plan(SoReq req, boolean forUpdate) {
        Partner customer = lookup.partner(req.customerId());
        if (customer.getKind() != PartnerKind.CUSTOMER) {
            throw new BizException(customer.getName() + " 不是客户");
        }
        Warehouse wh = lookup.warehouse(req.warehouseId());
        if (wh.getKind() == WarehouseKind.RETURN) {
            throw new BizException(wh.getName() + " 存放待处置的退货，不能直接出库；请先由质量人员处置转可售");
        }
        double minRatio = customer.getMinShelfPct() / 100.0;
        Set<Long> seen = new HashSet<>();
        List<Item> items = new ArrayList<>();
        List<FefoAllocator.Result> results = new ArrayList<>();
        for (SoLineReq l : req.lines()) {
            Item item = lookup.item(l.itemId());
            if (item.getItemType() != ItemType.FG) {
                throw new BizException("销售出库只能发成品，" + item.getName() + " 不是成品");
            }
            if (!seen.add(item.getId())) {
                throw new BizException(item.getName() + " 重复，请合并为一行");
            }
            items.add(item);
            results.add(forUpdate ? inventory.allocateForUpdate(item.getId(), l.qty(), List.of(wh.getId()), minRatio)
                    : inventory.allocate(item.getId(), l.qty(), List.of(wh.getId()), minRatio));
        }
        return new Plan(customer, wh, items, req.lines(), results);
    }

    private PreviewView toPreview(Plan plan) {
        List<AllocLineView> out = new ArrayList<>();
        for (int i = 0; i < plan.items().size(); i++) {
            Item item = plan.items().get(i);
            FefoAllocator.Result r = plan.results().get(i);
            out.add(new AllocLineView(item.getId(), item.getCode(), item.getName(), item.getUnit(), plan.lines().get(i).qty(),
                    r.picks().stream().map(p -> new PickView(p.batchId(), p.batchNo(), p.warehouseId(), p.qty(), p.expDate())).toList(),
                    r.shortQty(), r.excludedQty()));
        }
        return new PreviewView(plan.customer().getName(), plan.customer().getMinShelfPct(), plan.fulfilled(), out);
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

}
