package com.cosmetic.scm.sales;

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
    private final DocNoService docNo;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    public record SoLineReq(@NotNull Long itemId, @NotNull @Positive BigDecimal qty) {
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

    public record SoLineView(Long itemId, String itemCode, String itemName, BigDecimal qty) {
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
        return list.stream().map(so -> {
            Partner c = pm.get(so.getCustomerId());
            Warehouse w = wm.get(so.getWarehouseId());
            List<SoLineView> lv = lm.getOrDefault(so.getId(), List.of()).stream()
                    .map(l -> new SoLineView(l.getItemId(), im.get(l.getItemId()).getCode(), im.get(l.getItemId()).getName(), l.getQty()))
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
            SoLine line = new SoLine();
            line.setSoId(so.getId());
            line.setItemId(item.getId());
            line.setQty(plan.lines().get(i).qty());
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
