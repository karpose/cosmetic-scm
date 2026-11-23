package com.cosmetic.scm.integration;

import com.cosmetic.scm.common.Qty;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.ChannelOrderStatus;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.WarehouseKind;
import com.cosmetic.scm.domain.rules.FefoAllocator;
import com.cosmetic.scm.inventory.InventoryService;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.ItemRepository;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.PartnerRepository;
import com.cosmetic.scm.master.Warehouse;
import com.cosmetic.scm.master.WarehouseRepository;
import com.cosmetic.scm.sales.SalesOrder;
import com.cosmetic.scm.sales.SalesOrderRepository;
import com.cosmetic.scm.sales.SalesService;
import com.cosmetic.scm.sales.SalesService.SoLineReq;
import com.cosmetic.scm.sales.SalesService.SoReq;
import com.cosmetic.scm.sales.SalesService.SoView;
import com.cosmetic.scm.security.PriceAccess;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 电商对接骨架，和具体电商 ERP 无关：
 * <ul>
 *   <li>订单推送：同一平台同一订单号只收一次；每单单独一个事务出库（先到期先出 + 客户效期门槛），
 *       失败的单子记录原因、可在系统里重试，不影响同一批里的其它订单；</li>
 *   <li>库存查询：按客户的效期标准算可售数量，供 ERP 同步到店铺；</li>
 *   <li>每次调用写一条对接日志，独立事务，业务回滚时日志也保留。</li>
 * </ul>
 * 选定电商 ERP 后，只需写一个适配层把它的报文转成这里的请求即可。
 */
@Service
public class IntegrationService {

    private static final BigDecimal ALL = new BigDecimal("999999999");

    private final ChannelOrderRepository orders;
    private final ChannelOrderLineRepository lines;
    private final IntegrationLogRepository logs;
    private final PartnerRepository partners;
    private final ItemRepository items;
    private final WarehouseRepository warehouses;
    private final SalesOrderRepository salesOrders;
    private final SalesService sales;
    private final InventoryService inventory;
    private final MasterLookup lookup;
    private final TimeProvider time;
    private final TransactionTemplate tx;
    private final TransactionTemplate txNew;
    private final com.cosmetic.scm.audit.AuditService audit;

    public IntegrationService(ChannelOrderRepository orders, ChannelOrderLineRepository lines, IntegrationLogRepository logs,
                              PartnerRepository partners, ItemRepository items, WarehouseRepository warehouses,
                              SalesOrderRepository salesOrders, SalesService sales, InventoryService inventory,
                              MasterLookup lookup, TimeProvider time, PlatformTransactionManager txManager,
                              com.cosmetic.scm.audit.AuditService audit) {
        this.audit = audit;
        this.orders = orders;
        this.lines = lines;
        this.logs = logs;
        this.partners = partners;
        this.items = items;
        this.warehouses = warehouses;
        this.salesOrders = salesOrders;
        this.sales = sales;
        this.inventory = inventory;
        this.lookup = lookup;
        this.time = time;
        this.tx = new TransactionTemplate(txManager);
        this.txNew = new TransactionTemplate(txManager);
        this.txNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public static final String PLATFORM_REGEX = "^[A-Za-z0-9_]{2,20}$";

    public record PushLine(@NotBlank @Size(max = 40) String sku, @NotNull @Positive @Qty BigDecimal qty) {
    }

    public record PushOrder(@NotBlank @Size(max = 64) String orderNo, LocalDateTime orderedAt,
                            @NotEmpty @Valid List<PushLine> lines) {
    }

    public record PushReq(@NotBlank @Pattern(regexp = PLATFORM_REGEX, message = "平台代码只能用 2-20 位字母、数字、下划线") String platform,
                          @NotBlank(message = "请指定客户编码（往来单位里该店铺对应的客户）") String customerCode,
                          String warehouseCode,
                          @NotEmpty @Size(max = 200, message = "一次最多推送 200 单") @Valid List<PushOrder> orders) {
    }

    public record CancelReq(@NotBlank String platform, @NotBlank String orderNo) {
    }

    public record PushResult(String orderNo, ChannelOrderStatus status, String statusLabel, String soNo, String error,
                             boolean duplicate) {
    }

    public record StockRow(String sku, String name, String unit, BigDecimal available) {
    }

    public record LineView(String sku, String itemName, BigDecimal qty) {
    }

    public record OrderView(Long id, String platform, String platformOrderNo, String customerName, String warehouseName,
                            ChannelOrderStatus status, String statusLabel, String soNo, String error, int attempts,
                            LocalDateTime orderedAt, LocalDateTime receivedAt, LocalDateTime updatedAt,
                            List<LineView> lines) {
    }

    public record LogView(Long id, String direction, String platform, String action, String ref, boolean success,
                          String message, LocalDateTime createdAt) {
    }

    public record PlatformHealth(String platform, long totalCalls, long successCalls, long failedCalls,
                                 Double successRatePct, LocalDateTime lastCallAt, LocalDateTime lastFailureAt) {
    }

    public record FailureReasonRow(String platform, String action, String message, long count, LocalDateTime lastAt) {
    }

    public record IntegrationHealth(int days, long totalCalls, long successCalls, long failedCalls,
                                    Double successRatePct, long failedOrdersBacklog, long receivedOrdersBacklog,
                                    List<PlatformHealth> byPlatform, List<FailureReasonRow> topFailures) {
    }

    public record ExceptionRow(Long orderId, String riskLevel, String riskLabel, String causeCode, String causeLabel,
                               String platform, String platformOrderNo, String customerName, String warehouseName,
                               ChannelOrderStatus status, String statusLabel, int attempts, long agingHours,
                               LocalDateTime receivedAt, LocalDateTime updatedAt, String skuSummary,
                               String error, String recommendedAction) {
    }

    public record IntegrationExceptionBoard(int totalExceptions, int criticalCount, int highCount,
                                            int skuUnmappedCount, int stockShortageCount, int overdue24hCount,
                                            long recent7dFailedCalls, Double recent7dFailureRatePct,
                                            List<ExceptionRow> rows) {
    }

    // ---------- ERP 调用 ----------

    public List<PushResult> receive(PushReq req) {
        String platform = req.platform().toUpperCase();
        Partner customer;
        Warehouse wh;
        try {
            customer = customerByCode(req.customerCode());
            wh = shipFrom(req.warehouseCode());
        } catch (BizException e) {
            log("IN", platform, "推送订单", req.customerCode(), false, e.getMessage());
            throw e;
        }
        Map<String, Item> skuMap = items.findByItemTypeOrderByCode(ItemType.FG).stream()
                .filter(Item::isActive).collect(Collectors.toMap(Item::getCode, Function.identity()));
        List<PushResult> results = new ArrayList<>();
        for (PushOrder po : req.orders()) {
            Optional<ChannelOrder> existing = orders.findByPlatformAndPlatformOrderNo(platform, po.orderNo().trim());
            if (existing.isPresent()) {
                results.add(result(existing.get(), true));
                continue;
            }
            Long id;
            try {
                id = tx.execute(s -> saveOrder(platform, po, customer, wh, skuMap));
            } catch (DataIntegrityViolationException e) {
                // 并发推送了同一单：以先到的为准
                results.add(orders.findByPlatformAndPlatformOrderNo(platform, po.orderNo().trim())
                        .map(o -> result(o, true)).orElseThrow(() -> e));
                continue;
            }
            results.add(fulfil(id));
        }
        long failed = results.stream().filter(r -> r.status() == ChannelOrderStatus.FAILED).count();
        long dup = results.stream().filter(PushResult::duplicate).count();
        log("IN", platform, "推送订单", customer.getCode(), failed == 0,
                "共 " + results.size() + " 单，重复 " + dup + "，出库失败 " + failed);
        return results;
    }

    public PushResult cancel(CancelReq req) {
        String platform = req.platform().toUpperCase();
        ChannelOrder o = orders.findByPlatformAndPlatformOrderNo(platform, req.orderNo().trim())
                .orElseThrow(() -> BizException.notFound("订单 " + req.orderNo() + " "));
        return cancelOrder(o.getId(), "平台取消");
    }

    @Transactional(readOnly = true)
    public List<StockRow> stock(String customerCode) {
        Partner customer = customerByCode(customerCode);
        double minRatio = customer.getMinShelfPct() / 100.0;
        List<Long> own = warehouses.findByKind(WarehouseKind.OWN).stream().map(Warehouse::getId).toList();
        List<StockRow> rows = items.findByItemTypeOrderByCode(ItemType.FG).stream().filter(Item::isActive).map(i -> {
            FefoAllocator.Result r = inventory.allocate(i.getId(), ALL, own, minRatio);
            return new StockRow(i.getCode(), i.getName(), i.getUnit(),
                    r.picks().stream().map(FefoAllocator.Pick::qty).reduce(BigDecimal.ZERO, BigDecimal::add));
        }).toList();
        log("OUT", null, "查询可售库存", customer.getCode(), true, rows.size() + " 个 SKU");
        return rows;
    }

    // ---------- 系统内操作 ----------

    @Transactional(readOnly = true)
    public List<OrderView> list() {
        return toViews(orders.findTop300ByOrderByIdDesc());
    }

    @Transactional(readOnly = true)
    public List<LogView> recentLogs() {
        boolean canSeeFinance = com.cosmetic.scm.security.PriceAccess.allowed();
        return logs.findTop200ByOrderByIdDesc().stream()
                .filter(l -> canSeeFinance || !"FINANCE".equalsIgnoreCase(l.getPlatform()))
                .map(l -> new LogView(l.getId(), l.getDirection(), l.getPlatform(),
                        l.getAction(), l.getRef(), l.isSuccess(), l.getMessage(), l.getCreatedAt())).toList();
    }

    @Transactional(readOnly = true)
    public long failedCount() {
        return orders.findByStatus(ChannelOrderStatus.FAILED).size();
    }

    /** 统计近 N 天接口集成健康度（严格按 PriceAccess 过滤 FINANCE 平台日志）。 */
    @Transactional(readOnly = true)
    public IntegrationHealth health(int days) {
        int window = Math.max(1, Math.min(days, 365));
        boolean canSeeFinance = com.cosmetic.scm.security.PriceAccess.allowed();
        LocalDateTime since = time.now().minusDays(window);
        List<IntegrationLog> list = logs.findByCreatedAtGreaterThanEqualOrderByIdDesc(since).stream()
                .filter(l -> canSeeFinance || !"FINANCE".equalsIgnoreCase(l.getPlatform()))
                .toList();

        long total = list.size();
        long ok = list.stream().filter(IntegrationLog::isSuccess).count();
        long fail = total - ok;
        Double rate = total == 0 ? null : Math.round(ok * 1000.0 / total) / 10.0;

        Map<String, List<IntegrationLog>> byPlat = new java.util.LinkedHashMap<>();
        for (IntegrationLog l : list) {
            String p = (l.getPlatform() == null || l.getPlatform().isBlank()) ? "GENERAL" : l.getPlatform().toUpperCase();
            byPlat.computeIfAbsent(p, k -> new ArrayList<>()).add(l);
        }
        List<PlatformHealth> platformRows = byPlat.entrySet().stream()
                .map(e -> {
                    List<IntegrationLog> rows = e.getValue();
                    long pTotal = rows.size();
                    long pOk = rows.stream().filter(IntegrationLog::isSuccess).count();
                    long pFail = pTotal - pOk;
                    Double pRate = pTotal == 0 ? null : Math.round(pOk * 1000.0 / pTotal) / 10.0;
                    LocalDateTime lastAt = rows.get(0).getCreatedAt();
                    LocalDateTime lastFail = rows.stream().filter(x -> !x.isSuccess()).map(IntegrationLog::getCreatedAt)
                            .findFirst().orElse(null);
                    return new PlatformHealth(e.getKey(), pTotal, pOk, pFail, pRate, lastAt, lastFail);
                })
                .sorted(java.util.Comparator.comparingLong(PlatformHealth::failedCalls).reversed()
                        .thenComparing(java.util.Comparator.comparingLong(PlatformHealth::totalCalls).reversed()))
                .toList();

        Map<String, List<IntegrationLog>> failGroups = new java.util.LinkedHashMap<>();
        for (IntegrationLog l : list) {
            if (!l.isSuccess()) {
                String p = (l.getPlatform() == null || l.getPlatform().isBlank()) ? "GENERAL" : l.getPlatform().toUpperCase();
                String act = l.getAction() == null ? "—" : l.getAction();
                String msg = (l.getMessage() == null || l.getMessage().isBlank()) ? "未记录错误原因" : l.getMessage();
                failGroups.computeIfAbsent(p + "\0" + act + "\0" + msg, k -> new ArrayList<>()).add(l);
            }
        }
        List<FailureReasonRow> topFailures = failGroups.values().stream()
                .map(rows -> {
                    IntegrationLog first = rows.get(0);
                    String p = (first.getPlatform() == null || first.getPlatform().isBlank()) ? "GENERAL" : first.getPlatform().toUpperCase();
                    String act = first.getAction() == null ? "—" : first.getAction();
                    String msg = (first.getMessage() == null || first.getMessage().isBlank()) ? "未记录错误原因" : first.getMessage();
                    return new FailureReasonRow(p, act, msg, rows.size(), first.getCreatedAt());
                })
                .sorted(java.util.Comparator.comparingLong(FailureReasonRow::count).reversed())
                .limit(20)
                .toList();

        long failedBacklog = orders.findByStatus(ChannelOrderStatus.FAILED).size();
        long receivedBacklog = orders.findByStatus(ChannelOrderStatus.RECEIVED).size();
        return new IntegrationHealth(window, total, ok, fail, rate, failedBacklog, receivedBacklog, platformRows, topFailures);
    }

    /** 电商与接口集成异常监控与重试闭环看板：汇总所有失败待重试与滞留订单，按滞留时长与原因归类分级。 */
    @Transactional(readOnly = true)
    public IntegrationExceptionBoard exceptionBoard() {
        LocalDateTime now = time.now();
        boolean canSeeFinance = PriceAccess.allowed();
        List<ChannelOrder> open = new ArrayList<>();
        open.addAll(orders.findByStatus(ChannelOrderStatus.FAILED));
        open.addAll(orders.findByStatus(ChannelOrderStatus.RECEIVED));

        Map<Long, List<ChannelOrderLine>> lineMap = open.isEmpty() ? Map.of()
                : lines.findByOrderIdIn(open.stream().map(ChannelOrder::getId).toList())
                        .stream().collect(Collectors.groupingBy(ChannelOrderLine::getOrderId));
        Map<Long, Partner> pm = lookup.partnerMap();
        Map<Long, Warehouse> wm = lookup.warehouseMap();

        List<ExceptionRow> rows = new ArrayList<>();
        int critical = 0, high = 0, skuUnmapped = 0, stockShortage = 0, overdue24h = 0;

        for (ChannelOrder o : open) {
            List<ChannelOrderLine> ls = lineMap.getOrDefault(o.getId(), List.of());
            List<String> unmatched = ls.stream().filter(l -> l.getItemId() == null).map(ChannelOrderLine::getSku).toList();
            String skuSummary = ls.stream()
                    .map(l -> l.getSku() + "×" + l.getQty().stripTrailingZeros().toPlainString())
                    .collect(Collectors.joining("、"));
            String err = o.getError() == null ? "" : o.getError();

            String causeCode;
            String causeLabel;
            if (o.getStatus() == ChannelOrderStatus.RECEIVED) {
                causeCode = "STUCK_RECEIVED";
                causeLabel = "待出库滞留";
            } else if (err.contains("信用额度不足：授信 ¥")) {
                causeCode = "CREDIT_LIMIT";
                causeLabel = "客户信用额度不足";
            } else if (!unmatched.isEmpty() || err.contains("SKU") || err.contains("没有匹配到")) {
                causeCode = "SKU_UNMAPPED";
                causeLabel = "SKU 未匹配";
                skuUnmapped++;
            } else if (err.contains("不足") || err.contains("缺") || err.contains("库存") || err.contains("效期")) {
                causeCode = "STOCK_SHORTAGE";
                causeLabel = "库存/效期不足";
                stockShortage++;
            } else {
                causeCode = "OTHER_ERROR";
                causeLabel = "其它出库异常";
            }

            long agingHours = o.getReceivedAt() == null ? 0L
                    : Math.max(0L, java.time.Duration.between(o.getReceivedAt(), now).toHours());
            if (agingHours >= 24) {
                overdue24h++;
            }

            String riskLevel;
            String riskLabel;
            if (agingHours >= 24 || o.getAttempts() >= 3) {
                riskLevel = "CRITICAL";
                riskLabel = "严重滞留";
                critical++;
            } else if (agingHours >= 4 || "SKU_UNMAPPED".equals(causeCode)) {
                riskLevel = "HIGH";
                riskLabel = "高风险";
                high++;
            } else {
                riskLevel = "MEDIUM";
                riskLabel = "待重试";
            }

            String action = switch (causeCode) {
                case "SKU_UNMAPPED" -> "核对平台 SKU 与成品物料编码映射"
                        + (unmatched.isEmpty() ? "" : "（未匹配：" + String.join("、", unmatched) + "）")
                        + "，补建或启用成品后点击「重试出库」";
                case "STOCK_SHORTAGE" -> "核查自有仓成品可用库存与客户效期门槛，待放行或补货后点击「重试出库」";
                case "CREDIT_LIMIT" -> "请联系财务核对客户授信与回款，解除限制后点击「重试出库」";
                case "STUCK_RECEIVED" -> "订单已接收但未完成出库，请核查对接日志，确认不再发货后可取消";
                default -> o.getAttempts() >= 3
                        ? "已连续尝试 " + o.getAttempts() + " 次仍失败，请排查客户及仓库主数据，修正后重试"
                        : "核查失败原因后点击「重试出库」，若平台已退单请执行取消";
            };

            Partner cust = pm.get(o.getCustomerId());
            Warehouse wh = wm.get(o.getWarehouseId());
            rows.add(new ExceptionRow(
                    o.getId(), riskLevel, riskLabel, causeCode, causeLabel,
                    o.getPlatform(), o.getPlatformOrderNo(),
                    cust == null ? "—" : cust.getName(),
                    wh == null ? "—" : wh.getName(),
                    o.getStatus(), o.getStatus().getLabel(), o.getAttempts(), agingHours,
                    o.getReceivedAt(), o.getUpdatedAt(), skuSummary,
                    !canSeeFinance && "CREDIT_LIMIT".equals(causeCode) ? causeLabel : o.getError(), action));
        }

        rows.sort(java.util.Comparator
                .comparingInt((ExceptionRow r) -> "CRITICAL".equals(r.riskLevel()) ? 0 : "HIGH".equals(r.riskLevel()) ? 1 : 2)
                .thenComparing(java.util.Comparator.comparingLong(ExceptionRow::agingHours).reversed())
                .thenComparing(java.util.Comparator.comparingLong(ExceptionRow::orderId).reversed()));

        List<IntegrationLog> recent7d = logs.findByCreatedAtGreaterThanEqualOrderByIdDesc(now.minusDays(7)).stream()
                .filter(l -> canSeeFinance || !"FINANCE".equalsIgnoreCase(l.getPlatform()))
                .toList();
        long total7d = recent7d.size();
        long failed7d = recent7d.stream().filter(l -> !l.isSuccess()).count();
        Double failRate7d = total7d == 0 ? null : Math.round(failed7d * 1000.0 / total7d) / 10.0;

        return new IntegrationExceptionBoard(rows.size(), critical, high, skuUnmapped, stockShortage, overdue24h,
                failed7d, failRate7d, rows);
    }

    /** 按日期范围导出对接日志（无价格权限时过滤 FINANCE 平台日志）。 */
    @Transactional(readOnly = true)
    public List<LogView> exportLogs(java.time.LocalDate from, java.time.LocalDate to) {
        boolean canSeeFinance = com.cosmetic.scm.security.PriceAccess.allowed();
        LocalDateTime start = from == null ? java.time.LocalDate.of(2000, 1, 1).atStartOfDay() : from.atStartOfDay();
        LocalDateTime end = to == null ? java.time.LocalDate.of(2999, 1, 1).atStartOfDay() : to.plusDays(1).atStartOfDay();
        return logs.findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByIdDesc(start, end).stream()
                .filter(l -> canSeeFinance || !"FINANCE".equalsIgnoreCase(l.getPlatform()))
                .map(l -> new LogView(l.getId(), l.getDirection(), l.getPlatform(),
                        l.getAction(), l.getRef(), l.isSuccess(), l.getMessage(), l.getCreatedAt()))
                .toList();
    }

    public PushResult retry(Long id) {
        ChannelOrder o = tx.execute(st -> {
            ChannelOrder order = orders.lockById(id).orElseThrow(() -> BizException.notFound("电商订单"));
            if (order.getStatus() != ChannelOrderStatus.FAILED) {
                throw new BizException("只有出库失败的订单可以重试，当前为" + order.getStatus().getLabel());
            }
            refreshUnmappedSkus(id);
            return order;
        });
        PushResult r = fulfil(id);
        // 对接日志不记操作人，手工操作要进审计日志
        tx.executeWithoutResult(st -> audit.log("重试电商订单", o.getPlatformOrderNo(), r.statusLabel()
                + (r.soNo() != null ? "，出库单 " + r.soNo() : r.error() != null ? "：" + r.error() : "")));
        log("IN", o.getPlatform(), "手工重试", o.getPlatformOrderNo(), r.status() == ChannelOrderStatus.SHIPPED,
                r.error() != null ? r.error() : r.soNo() != null ? "出库单 " + r.soNo() : r.statusLabel());
        return r;
    }

    public PushResult cancelOrder(Long id, String reason) {
        PushResult r = tx.execute(s -> {
            ChannelOrder o = orders.lockById(id).orElseThrow(() -> BizException.notFound("电商订单"));
            if (o.getStatus() == ChannelOrderStatus.SHIPPED) {
                throw new BizException("订单 " + o.getPlatformOrderNo() + " 已出库，不能取消；请走销售退货");
            }
            o.setStatus(ChannelOrderStatus.CANCELLED);
            o.setError(null);
            o.setUpdatedAt(time.now());
            orders.save(o);
            audit.log("取消电商订单", o.getPlatformOrderNo(), o.getPlatform(), reason);
            return result(o, false);
        });
        log("IN", null, "取消订单", r.orderNo(), true, reason);
        return r;
    }

    // ---------- 内部 ----------

    /** 在重试出库前保存已补齐的 SKU 映射，库存仍不足时也保留映射结果。 */
    private void refreshUnmappedSkus(Long orderId) {
        List<ChannelOrderLine> unmapped = lines.findByOrderIdIn(List.of(orderId)).stream()
                .filter(line -> line.getItemId() == null)
                .toList();
        if (unmapped.isEmpty()) {
            return;
        }
        List<String> codes = unmapped.stream().map(ChannelOrderLine::getSku).distinct().toList();
        Map<String, Long> matched = items.findByItemTypeAndActiveTrueAndCodeIn(ItemType.FG, codes).stream()
                .collect(Collectors.toMap(Item::getCode, Item::getId));
        for (ChannelOrderLine line : unmapped) {
            Long itemId = matched.get(line.getSku());
            if (itemId != null) {
                line.setItemId(itemId);
            }
        }
    }

    private Long saveOrder(String platform, PushOrder po, Partner customer, Warehouse wh, Map<String, Item> skuMap) {
        LocalDateTime now = time.now();
        ChannelOrder o = new ChannelOrder();
        o.setPlatform(platform);
        o.setPlatformOrderNo(po.orderNo().trim());
        o.setCustomerId(customer.getId());
        o.setWarehouseId(wh.getId());
        o.setStatus(ChannelOrderStatus.RECEIVED);
        o.setOrderedAt(po.orderedAt());
        o.setReceivedAt(now);
        o.setUpdatedAt(now);
        orders.saveAndFlush(o);
        for (PushLine pl : po.lines()) {
            ChannelOrderLine l = new ChannelOrderLine();
            l.setOrderId(o.getId());
            l.setSku(pl.sku().trim());
            Item item = skuMap.get(pl.sku().trim());
            l.setItemId(item == null ? null : item.getId());
            l.setQty(pl.qty());
            lines.save(l);
        }
        return o.getId();
    }

    /** 尝试出库：成功则已出库；业务原因失败（缺货、SKU 未匹配等）则标记失败并记录原因。 */
    private PushResult fulfil(Long id) {
        try {
            return tx.execute(s -> {
                // 锁住订单再判断：连点两次重试，或重试和取消同时发生，都不能重复出库
                ChannelOrder o = orders.lockById(id).orElseThrow();
                if (o.getStatus() == ChannelOrderStatus.SHIPPED || o.getStatus() == ChannelOrderStatus.CANCELLED) {
                    return result(o, false);
                }
                List<ChannelOrderLine> ls = lines.findByOrderIdIn(List.of(id));
                List<String> unmatched = ls.stream().filter(l -> l.getItemId() == null).map(ChannelOrderLine::getSku).toList();
                if (!unmatched.isEmpty()) {
                    throw new BizException("SKU 没有匹配到在售成品：" + String.join("、", unmatched) + "（按物料编码匹配）");
                }
                Map<Long, BigDecimal> qty = ls.stream().collect(Collectors.toMap(ChannelOrderLine::getItemId,
                        ChannelOrderLine::getQty, BigDecimal::add, java.util.LinkedHashMap::new));
                SoView so = sales.create(new SoReq(o.getCustomerId(), o.getWarehouseId(),
                        qty.entrySet().stream().map(e -> new SoLineReq(e.getKey(), e.getValue())).toList()));
                o.setStatus(ChannelOrderStatus.SHIPPED);
                o.setSoId(so.id());
                o.setError(null);
                o.setAttempts(o.getAttempts() + 1);
                o.setUpdatedAt(time.now());
                orders.save(o);
                return result(o, false);
            });
        } catch (BizException e) {
            return tx.execute(s -> {
                ChannelOrder o = orders.lockById(id).orElseThrow();
                // 出库失败回滚后到这里之间，订单可能已被取消。不能盖掉，否则取消的单又能重试出库
                if (o.getStatus() == ChannelOrderStatus.SHIPPED || o.getStatus() == ChannelOrderStatus.CANCELLED) {
                    return result(o, false);
                }
                o.setStatus(ChannelOrderStatus.FAILED);
                o.setError(truncate(e.getMessage(), 500));
                o.setAttempts(o.getAttempts() + 1);
                o.setUpdatedAt(time.now());
                orders.save(o);
                return result(o, false);
            });
        }
    }

    private Partner customerByCode(String code) {
        Partner p = partners.findByCode(code == null ? "" : code.trim())
                .orElseThrow(() -> new BizException("客户编码 " + code + " 不存在，请先在主数据里建好店铺对应的客户"));
        if (p.getKind() != PartnerKind.CUSTOMER) {
            throw new BizException(code + " 不是客户");
        }
        return p;
    }

    private Warehouse shipFrom(String code) {
        List<Warehouse> own = warehouses.findByKind(WarehouseKind.OWN);
        if (code == null || code.isBlank()) {
            return own.stream().findFirst().orElseThrow(() -> new BizException("没有自有仓，无法出库"));
        }
        return own.stream().filter(w -> w.getCode().equals(code.trim())).findFirst()
                .orElseThrow(() -> new BizException("发货仓 " + code + " 不存在或不是自有仓"));
    }

    private PushResult result(ChannelOrder o, boolean duplicate) {
        String soNo = o.getSoId() == null ? null : salesOrders.findById(o.getSoId()).map(SalesOrder::getSoNo).orElse(null);
        return new PushResult(o.getPlatformOrderNo(), o.getStatus(), o.getStatus().getLabel(), soNo, o.getError(), duplicate);
    }

    private void log(String direction, String platform, String action, String ref, boolean success, String message) {
        txNew.executeWithoutResult(s -> {
            IntegrationLog l = new IntegrationLog();
            l.setDirection(direction);
            l.setPlatform(platform);
            l.setAction(action);
            l.setRef(truncate(ref, 64));
            l.setSuccess(success);
            l.setMessage(truncate(message, 500));
            l.setCreatedAt(time.now());
            logs.save(l);
        });
    }

    private List<OrderView> toViews(List<ChannelOrder> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, List<ChannelOrderLine>> lm = lines.findByOrderIdIn(list.stream().map(ChannelOrder::getId).toList())
                .stream().collect(Collectors.groupingBy(ChannelOrderLine::getOrderId));
        Map<Long, String> soNos = salesOrders.findAllById(list.stream().map(ChannelOrder::getSoId)
                        .filter(java.util.Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(SalesOrder::getId, SalesOrder::getSoNo));
        Map<Long, Item> im = lookup.itemMap();
        Map<Long, Partner> pm = lookup.partnerMap();
        Map<Long, Warehouse> wm = lookup.warehouseMap();
        return list.stream().map(o -> new OrderView(o.getId(), o.getPlatform(), o.getPlatformOrderNo(),
                pm.get(o.getCustomerId()).getName(), wm.get(o.getWarehouseId()).getName(), o.getStatus(),
                o.getStatus().getLabel(), soNos.get(o.getSoId()), o.getError(), o.getAttempts(), o.getOrderedAt(),
                o.getReceivedAt(), o.getUpdatedAt(), lm.getOrDefault(o.getId(), List.of()).stream()
                .map(l -> new LineView(l.getSku(), l.getItemId() == null ? null : im.get(l.getItemId()).getName(), l.getQty()))
                .toList())).toList();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
