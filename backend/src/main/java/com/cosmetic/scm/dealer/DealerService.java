package com.cosmetic.scm.dealer;

import java.math.BigDecimal;
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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.DocNoService;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.DealerOrderStatus;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.WarehouseKind;
import com.cosmetic.scm.domain.rules.FefoAllocator;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.inventory.InventoryService;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.ItemRepository;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.Warehouse;
import com.cosmetic.scm.master.WarehouseRepository;
import com.cosmetic.scm.recall.Recall;
import com.cosmetic.scm.recall.RecallNotice;
import com.cosmetic.scm.recall.RecallNoticeRepository;
import com.cosmetic.scm.recall.RecallRepository;
import com.cosmetic.scm.sales.SalesOrder;
import com.cosmetic.scm.sales.SalesOrderRepository;
import com.cosmetic.scm.sales.SalesService;
import com.cosmetic.scm.sales.SalesService.SoLineReq;
import com.cosmetic.scm.sales.SalesService.SoReq;
import com.cosmetic.scm.sales.SalesService.SoView;
import com.cosmetic.scm.sales.SoAllocation;
import com.cosmetic.scm.sales.SoAllocationRepository;
import com.cosmetic.scm.security.CurrentUser;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 经销商订货。经销商在门户按“可订数量”下单；可订数量用和出库完全相同的规则计算
 * （只算自有仓已放行库存，排除退货隔离区，满足该经销商的效期接收标准）。
 * 销售审核通过时在同一事务里生成销售出库单，按先到期先出扣库存；库存不够则整单不通过。
 * 经销商还能看到发给自己的批号和召回通知，并在门户确认收到召回通知。
 */
@Service
@RequiredArgsConstructor
public class DealerService {

    /** 查可订数量时的“足够大”需求量：分配结果就是全部可用库存。 */
    private static final BigDecimal ALL = new BigDecimal("999999999");

    private final DealerOrderRepository orders;
    private final DealerOrderLineRepository lines;
    private final ItemRepository items;
    private final WarehouseRepository warehouses;
    private final BatchRepository batches;
    private final SalesOrderRepository salesOrders;
    private final SoAllocationRepository allocations;
    private final RecallRepository recalls;
    private final RecallNoticeRepository notices;
    private final InventoryService inventory;
    private final SalesService sales;
    private final MasterLookup lookup;
    private final DocNoService docNo;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    public record LineReq(@NotNull Long itemId, @NotNull @Positive BigDecimal qty) {
    }

    public record OrderReq(LocalDate requestedDate, @Size(max = 300) String note,
                           @NotEmpty(message = "至少订一个品项") @Valid List<LineReq> lines) {
    }

    public record ApproveReq(@NotNull(message = "请选择发货仓") Long warehouseId) {
    }

    public record RejectReq(@NotBlank(message = "请写明驳回原因") @Size(max = 300) String note) {
    }

    public record CatalogItem(Long itemId, String code, String name, String unit, String filingNo, BigDecimal available) {
    }

    public record LineView(Long itemId, String itemCode, String itemName, String unit, BigDecimal qty) {
    }

    public record ShippedView(String batchNo, String itemName, BigDecimal qty, LocalDate expDate) {
    }

    public record OrderView(Long id, String orderNo, Long customerId, String customerName, int minShelfPct,
                            DealerOrderStatus status, String statusLabel, LocalDate requestedDate, String note,
                            List<LineView> lines, String soNo, List<ShippedView> shipped, String reviewNote,
                            String reviewedBy, LocalDateTime reviewedAt, String createdBy, LocalDateTime createdAt) {
    }

    public record RecallNoticeView(Long noticeId, String recallNo, String itemName, String batchNo, String reason,
                                   String statusLabel, BigDecimal shippedQty, LocalDateTime openedAt,
                                   LocalDateTime notifiedAt, String notifiedBy, String note) {
    }

    // ---------- 经销商门户 ----------

    @Transactional(readOnly = true)
    public List<CatalogItem> catalog() {
        Partner me = lookup.partner(currentUser.portalPartnerId());
        List<Long> own = ownWarehouseIds();
        double minRatio = me.getMinShelfPct() / 100.0;
        return items.findByItemTypeOrderByCode(ItemType.FG).stream().filter(Item::isActive).map(i -> {
            FefoAllocator.Result r = inventory.allocate(i.getId(), ALL, own, minRatio);
            BigDecimal available = r.picks().stream().map(FefoAllocator.Pick::qty).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new CatalogItem(i.getId(), i.getCode(), i.getName(), i.getUnit(), i.getFilingNo(), available);
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<OrderView> myOrders() {
        return toViews(orders.findByCustomerIdOrderByIdDesc(currentUser.portalPartnerId()));
    }

    @Transactional
    public OrderView submit(OrderReq req) {
        Partner me = lookup.partner(currentUser.portalPartnerId());
        if (req.requestedDate() != null && req.requestedDate().isBefore(time.today())) {
            throw new BizException("期望到货日期不能早于今天");
        }
        DealerOrder o = new DealerOrder();
        o.setOrderNo(docNo.next("DO", time.today()));
        o.setCustomerId(me.getId());
        o.setStatus(DealerOrderStatus.SUBMITTED);
        o.setRequestedDate(req.requestedDate());
        o.setNote(blankToNull(req.note()));
        o.setCreatedBy(currentUser.usernameOrSystem());
        o.setCreatedAt(time.now());
        orders.save(o);
        Set<Long> seen = new HashSet<>();
        List<String> detail = new ArrayList<>();
        for (LineReq l : req.lines()) {
            Item item = lookup.item(l.itemId());
            if (item.getItemType() != ItemType.FG || !item.isActive()) {
                throw new BizException(item.getName() + " 不在可订商品里");
            }
            if (!seen.add(item.getId())) {
                throw new BizException(item.getName() + " 重复，请合并为一行");
            }
            DealerOrderLine line = new DealerOrderLine();
            line.setOrderId(o.getId());
            line.setItemId(item.getId());
            line.setQty(l.qty());
            lines.save(line);
            detail.add(item.getName() + "×" + plain(l.qty()));
        }
        audit.log("经销商下单", o.getOrderNo(), me.getName() + "：" + String.join("，", detail), o.getNote());
        return single(o);
    }

    @Transactional
    public OrderView cancel(Long id) {
        DealerOrder o = mine(id);
        if (o.getStatus() != DealerOrderStatus.SUBMITTED) {
            throw new BizException("订单当前为" + o.getStatus().getLabel() + "，不能撤回");
        }
        o.setStatus(DealerOrderStatus.CANCELLED);
        orders.save(o);
        audit.log("经销商撤回订单", o.getOrderNo(), "");
        return single(o);
    }

    @Transactional(readOnly = true)
    public List<RecallNoticeView> myRecalls() {
        List<RecallNotice> list = notices.findByCustomerIdOrderByIdDesc(currentUser.portalPartnerId());
        Map<Long, Recall> rm = recalls.findAllById(list.stream().map(RecallNotice::getRecallId).distinct().toList())
                .stream().collect(Collectors.toMap(Recall::getId, Function.identity()));
        Map<Long, Batch> bm = batches.findAllById(rm.values().stream().map(Recall::getBatchId).distinct().toList())
                .stream().collect(Collectors.toMap(Batch::getId, Function.identity()));
        Map<Long, Item> im = lookup.itemMap();
        return list.stream().map(n -> {
            Recall r = rm.get(n.getRecallId());
            Batch b = bm.get(r.getBatchId());
            return new RecallNoticeView(n.getId(), r.getRecallNo(), im.get(b.getItemId()).getName(), b.getBatchNo(),
                    r.getReason(), r.getStatus().getLabel(), n.getShippedQty(), r.getOpenedAt(), n.getNotifiedAt(),
                    n.getNotifiedBy(), n.getNote());
        }).toList();
    }

    /** 经销商在门户确认收到召回通知，等同于品牌方完成了对该客户的通知。 */
    @Transactional
    public List<RecallNoticeView> acknowledgeRecall(Long noticeId) {
        Long me = currentUser.portalPartnerId();
        RecallNotice n = notices.lockById(noticeId).filter(x -> me.equals(x.getCustomerId()))
                .orElseThrow(() -> BizException.notFound("召回通知"));
        if (n.getNotifiedAt() != null) {
            throw new BizException("该召回通知已于 " + n.getNotifiedAt().toLocalDate() + " 确认");
        }
        n.setNotifiedAt(time.now());
        n.setNotifiedBy(currentUser.usernameOrSystem());
        n.setNote("经销商在门户确认收到，停止销售并安排退回");
        notices.save(n);
        audit.log("经销商确认召回通知", recalls.findById(n.getRecallId()).map(Recall::getRecallNo).orElse(""),
                lookup.partner(me).getName());
        return myRecalls();
    }

    // ---------- 品牌方审核 ----------

    @Transactional(readOnly = true)
    public List<OrderView> list() {
        return toViews(orders.findTop300ByOrderByIdDesc());
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        return orders.findByStatus(DealerOrderStatus.SUBMITTED).size();
    }

    @Transactional
    public OrderView approve(Long id, ApproveReq req) {
        DealerOrder o = pending(id);
        List<SoLineReq> soLines = lines.findByOrderIdIn(List.of(o.getId())).stream()
                .map(l -> new SoLineReq(l.getItemId(), l.getQty())).toList();
        // 库存不够、效期不达标时 SalesService 会抛错，整笔审核回滚，订单保持待审核
        SoView so = sales.create(new SoReq(o.getCustomerId(), req.warehouseId(), soLines));
        o.setStatus(DealerOrderStatus.APPROVED);
        o.setSoId(so.id());
        o.setReviewedBy(currentUser.usernameOrSystem());
        o.setReviewedAt(time.now());
        orders.save(o);
        audit.log("审核经销商订单", o.getOrderNo(), "通过，生成出库单 " + so.soNo());
        return single(o);
    }

    @Transactional
    public OrderView reject(Long id, RejectReq req) {
        DealerOrder o = pending(id);
        o.setStatus(DealerOrderStatus.REJECTED);
        o.setReviewNote(req.note().trim());
        o.setReviewedBy(currentUser.usernameOrSystem());
        o.setReviewedAt(time.now());
        orders.save(o);
        audit.log("审核经销商订单", o.getOrderNo(), "驳回", o.getReviewNote());
        return single(o);
    }

    // ---------- 内部 ----------

    private DealerOrder mine(Long id) {
        Long me = currentUser.portalPartnerId();
        return orders.lockById(id).filter(o -> me.equals(o.getCustomerId()))
                .orElseThrow(() -> BizException.notFound("订单"));
    }

    private DealerOrder pending(Long id) {
        DealerOrder o = orders.lockById(id).orElseThrow(() -> BizException.notFound("经销商订单"));
        if (o.getStatus() != DealerOrderStatus.SUBMITTED) {
            throw new BizException("订单当前为" + o.getStatus().getLabel() + "，不能再审核");
        }
        return o;
    }

    private List<Long> ownWarehouseIds() {
        return warehouses.findByKind(WarehouseKind.OWN).stream().map(Warehouse::getId).toList();
    }

    private OrderView single(DealerOrder o) {
        return toViews(List.of(o)).get(0);
    }

    private List<OrderView> toViews(List<DealerOrder> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, List<DealerOrderLine>> lm = lines.findByOrderIdIn(list.stream().map(DealerOrder::getId).toList())
                .stream().collect(Collectors.groupingBy(DealerOrderLine::getOrderId));
        List<Long> soIds = list.stream().map(DealerOrder::getSoId).filter(java.util.Objects::nonNull).toList();
        Map<Long, String> soNos = salesOrders.findAllById(soIds).stream()
                .collect(Collectors.toMap(SalesOrder::getId, SalesOrder::getSoNo));
        List<SoAllocation> allocs = soIds.isEmpty() ? List.of() : allocations.findBySoIdIn(soIds);
        Map<Long, Batch> bm = batches.findAllById(allocs.stream().map(SoAllocation::getBatchId).distinct().toList())
                .stream().collect(Collectors.toMap(Batch::getId, Function.identity()));
        Map<Long, List<SoAllocation>> am = allocs.stream().collect(Collectors.groupingBy(SoAllocation::getSoId));
        Map<Long, Item> im = lookup.itemMap();
        Map<Long, Partner> pm = lookup.partnerMap();
        return list.stream().map(o -> {
            Partner c = pm.get(o.getCustomerId());
            List<LineView> lv = lm.getOrDefault(o.getId(), List.of()).stream().map(l -> {
                Item i = im.get(l.getItemId());
                return new LineView(i.getId(), i.getCode(), i.getName(), i.getUnit(), l.getQty());
            }).toList();
            List<ShippedView> sv = o.getSoId() == null ? List.of() : am.getOrDefault(o.getSoId(), List.of()).stream()
                    .map(a -> {
                        Batch b = bm.get(a.getBatchId());
                        return new ShippedView(b.getBatchNo(), im.get(a.getItemId()).getName(), a.getQty(), b.getExpDate());
                    }).toList();
            return new OrderView(o.getId(), o.getOrderNo(), c.getId(), c.getName(), c.getMinShelfPct(), o.getStatus(),
                    o.getStatus().getLabel(), o.getRequestedDate(), o.getNote(), lv, soNos.get(o.getSoId()), sv,
                    o.getReviewNote(), o.getReviewedBy(), o.getReviewedAt(), o.getCreatedBy(), o.getCreatedAt());
        }).toList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
