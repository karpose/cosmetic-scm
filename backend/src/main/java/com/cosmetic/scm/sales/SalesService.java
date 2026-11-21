package com.cosmetic.scm.sales;

import com.cosmetic.scm.common.Qty;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
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
    private final CustomerPriceService customerPrices;
    private final com.cosmetic.scm.settlement.CustomerStatementRepository customerStatements;
    private final DocNoService docNo;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    private static final Set<String> SALES_PRICE_ROLES = Set.of("ROLE_ADMIN", "ROLE_SALES", "ROLE_FINANCE");

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

    public record SoLineView(Long itemId, String itemCode, String itemName, BigDecimal qty,
                             BigDecimal unitPrice, BigDecimal taxRate, BigDecimal amount) {
    }

    public record AllocView(Long batchId, String batchNo, Long itemId, String itemName, BigDecimal qty) {
    }

    public record SoView(Long id, String soNo, Long customerId, String customerName, String channel,
                         Long warehouseId, String warehouseName, LocalDate orderDate, String status,
                         String createdBy, LocalDateTime createdAt, List<SoLineView> lines,
                         List<AllocView> allocations) {
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
        return views(orders.findTop300ByOrderByIdDesc());
    }

    /** 全部出库单，导出用（页面列表只取最近 300 张）。 */
    @Transactional(readOnly = true)
    public List<SoView> listAll() {
        return views(orders.findAllByOrderByIdDesc());
    }

    private List<SoView> views(List<SalesOrder> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<Long> ids = list.stream().map(SalesOrder::getId).toList();
        Map<Long, List<SoLine>> lm = lines.findBySoIdIn(ids).stream().collect(Collectors.groupingBy(SoLine::getSoId));
        List<SoAllocation> allocs = allocations.findBySoIdIn(ids);
        Map<Long, List<SoAllocation>> am = allocs.stream().collect(Collectors.groupingBy(SoAllocation::getSoId));
        Map<Long, Batch> bm = batches.findAllById(allocs.stream().map(SoAllocation::getBatchId).distinct().toList())
                .stream().collect(Collectors.toMap(Batch::getId, Function.identity()));
        Map<Long, Item> im = lookup.itemMap();
        Map<Long, Partner> pm = lookup.partnerMap();
        Map<Long, Warehouse> wm = lookup.warehouseMap();
        boolean showPrice = salesPriceAllowed();
        return list.stream().map(so -> {
            Partner c = pm.get(so.getCustomerId());
            Warehouse w = wm.get(so.getWarehouseId());
            List<SoLineView> lv = lm.getOrDefault(so.getId(), List.of()).stream()
                    .map(l -> {
                        BigDecimal up = showPrice ? l.getUnitPrice() : null;
                        BigDecimal tr = showPrice ? l.getTaxRate() : null;
                        BigDecimal amt = up != null ? l.getQty().multiply(up).setScale(2, RoundingMode.HALF_UP) : null;
                        return new SoLineView(l.getItemId(), im.get(l.getItemId()).getCode(), im.get(l.getItemId()).getName(), l.getQty(), up, tr, amt);
                    })
                    .toList();
            List<AllocView> av = am.getOrDefault(so.getId(), List.of()).stream()
                    .map(a -> new AllocView(a.getBatchId(), bm.get(a.getBatchId()).getBatchNo(), a.getItemId(),
                            im.get(a.getItemId()).getName(), a.getQty()))
                    .toList();
            return new SoView(so.getId(), so.getSoNo(), so.getCustomerId(), c.getName(), c.getChannel(),
                    so.getWarehouseId(), w.getName(), so.getOrderDate(), so.getStatus(), so.getCreatedBy(),
                    so.getCreatedAt(), lv, av);
        }).toList();
    }

    /** 只计算分配结果，不扣库存，供页面预览。 */
    @Transactional(readOnly = true)
    public PreviewView preview(SoReq req) {
        return toPreview(plan(req, false));
    }

    @Transactional
    public SoView create(SoReq req) {
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
                    throw new BizException(org.springframework.http.HttpStatus.FORBIDDEN, "没有权限录入销售单价");
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
        BigDecimal openAr = customerStatements.findByCustomerIdOrderByIdDesc(customer.getId()).stream()
                .filter(s -> !"PAID".equals(s.getStatus()) && !"CANCELLED".equals(s.getStatus()))
                .map(com.cosmetic.scm.settlement.CustomerStatement::outstandingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (openAr.add(orderTotal).compareTo(customer.getCreditLimit()) > 0) {
            throw new BizException("客户 " + customer.getName() + " 信用额度不足：授信 ¥" + customer.getCreditLimit().toPlainString()
                    + "，当前未结应收 ¥" + openAr.toPlainString() + "，本次出库 ¥" + orderTotal.toPlainString()
                    + "，请先回款核销或调整授信额度");
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
}
