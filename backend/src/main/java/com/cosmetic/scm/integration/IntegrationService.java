package com.cosmetic.scm.integration;

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

    public IntegrationService(ChannelOrderRepository orders, ChannelOrderLineRepository lines, IntegrationLogRepository logs,
                              PartnerRepository partners, ItemRepository items, WarehouseRepository warehouses,
                              SalesOrderRepository salesOrders, SalesService sales, InventoryService inventory,
                              MasterLookup lookup, TimeProvider time, PlatformTransactionManager txManager) {
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

    public record PushLine(@NotBlank @Size(max = 40) String sku, @NotNull @Positive BigDecimal qty) {
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
        return logs.findTop200ByOrderByIdDesc().stream().map(l -> new LogView(l.getId(), l.getDirection(), l.getPlatform(),
                l.getAction(), l.getRef(), l.isSuccess(), l.getMessage(), l.getCreatedAt())).toList();
    }

    @Transactional(readOnly = true)
    public long failedCount() {
        return orders.findByStatus(ChannelOrderStatus.FAILED).size();
    }

    public PushResult retry(Long id) {
        ChannelOrder o = orders.findById(id).orElseThrow(() -> BizException.notFound("电商订单"));
        if (o.getStatus() != ChannelOrderStatus.FAILED) {
            throw new BizException("只有出库失败的订单可以重试，当前为" + o.getStatus().getLabel());
        }
        PushResult r = fulfil(id);
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
            return result(o, false);
        });
        log("IN", null, "取消订单", r.orderNo(), true, reason);
        return r;
    }

    // ---------- 内部 ----------

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
