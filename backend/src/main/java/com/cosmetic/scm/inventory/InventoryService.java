package com.cosmetic.scm.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.SourceType;
import com.cosmetic.scm.domain.TxnType;
import com.cosmetic.scm.domain.rules.FefoAllocator;
import com.cosmetic.scm.domain.rules.ShelfLife;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.Warehouse;
import com.cosmetic.scm.security.CurrentUser;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 库存的唯一入口：建批次、增减库存、按 FEFO 分配。
 * 每次数量变化都锁住结存行并写一条流水；数量不允许为负。
 */
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final BatchRepository batches;
    private final StockRepository stocks;
    private final StockTxnRepository txns;
    private final MasterLookup lookup;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    public record BatchView(Long id, String batchNo, Long itemId, String itemCode, String itemName, String itemType,
                            String unit, LocalDate mfgDate, LocalDate expDate, Double shelfRatio,
                            BatchStatus status, String statusLabel, SourceType sourceType, String sourceNo,
                            Long partnerId, String partnerName, String extBatchNo, BigDecimal qtyIn,
                            BigDecimal onHand, boolean oemReleased, String freezeReason, LocalDateTime createdAt) {
    }

    public record StockRow(Long stockId, Long batchId, String batchNo, Long itemId, String itemCode, String itemName,
                           String itemType, String unit, Long warehouseId, String warehouseName, BigDecimal qty,
                           BatchStatus status, String statusLabel, LocalDate mfgDate, LocalDate expDate,
                           Double shelfRatio) {
    }

    public record TxnView(Long id, String warehouseName, BigDecimal qtyDelta, String txnType, String refNo,
                          String operator, LocalDateTime createdAt) {
    }

    // ---------- 写操作（必须在业务事务内调用） ----------

    @Transactional(propagation = Propagation.MANDATORY)
    public Batch createBatch(String batchNo, Item item, LocalDate mfgDate, LocalDate expDate, BatchStatus status,
                             SourceType sourceType, String sourceNo, Long partnerId, String extBatchNo,
                             BigDecimal qtyIn) {
        Batch b = new Batch();
        b.setBatchNo(batchNo);
        b.setItemId(item.getId());
        b.setMfgDate(mfgDate);
        b.setExpDate(expDate);
        b.setStatus(status);
        b.setSourceType(sourceType);
        b.setSourceNo(sourceNo);
        b.setPartnerId(partnerId);
        b.setExtBatchNo(extBatchNo);
        b.setQtyIn(qtyIn);
        b.setCreatedAt(time.now());
        return batches.save(b);
    }

    /** 增减某批次在某仓库的数量。减少时不足会抛错，整个业务事务回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public Stock change(Batch batch, Long warehouseId, BigDecimal delta, TxnType type, String refNo) {
        if (delta == null || delta.signum() == 0) {
            throw new IllegalArgumentException("库存变动数量不能为 0");
        }
        Stock s = stocks.lockByBatchAndWarehouse(batch.getId(), warehouseId).orElseGet(() -> {
            Stock n = new Stock();
            n.setBatchId(batch.getId());
            n.setItemId(batch.getItemId());
            n.setWarehouseId(warehouseId);
            n.setQty(BigDecimal.ZERO);
            return n;
        });
        BigDecimal after = s.getQty().add(delta);
        if (after.signum() < 0) {
            Warehouse w = lookup.warehouse(warehouseId);
            throw new BizException("库存不足：批次 " + batch.getBatchNo() + " 在 " + w.getName() + " 只有 "
                    + s.getQty().stripTrailingZeros().toPlainString());
        }
        s.setQty(after);
        stocks.save(s);

        StockTxn t = new StockTxn();
        t.setBatchId(batch.getId());
        t.setWarehouseId(warehouseId);
        t.setQtyDelta(delta);
        t.setTxnType(type);
        t.setRefNo(refNo);
        t.setOperator(currentUser.usernameOrSystem());
        t.setCreatedAt(time.now());
        txns.save(t);
        return s;
    }

    /**
     * 扣减一组 FEFO 分配结果。执行时再次确认批次仍是“已放行”，防止分配后批次被冻结。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(List<FefoAllocator.Pick> picks, TxnType type, String refNo) {
        for (FefoAllocator.Pick p : picks) {
            Batch b = batch(p.batchId());
            if (b.getStatus() != BatchStatus.RELEASED) {
                throw new BizException("批次 " + b.getBatchNo() + " 当前状态为" + b.getStatus().getLabel() + "，不能出库");
            }
            change(b, p.warehouseId(), p.qty().negate(), type, refNo);
        }
    }

    // ---------- 分配（只读） ----------

    /**
     * 按 FEFO 为某物料分配已放行库存。
     *
     * @param warehouseIds 限定仓库，为空表示不限
     * @param minRatio     最低剩余效期比例，0~1
     */
    @Transactional(readOnly = true)
    public FefoAllocator.Result allocate(Long itemId, BigDecimal qty, Collection<Long> warehouseIds, double minRatio) {
        List<Stock> rows = stocks.findByItemIdAndQtyGreaterThan(itemId, BigDecimal.ZERO).stream()
                .filter(s -> warehouseIds == null || warehouseIds.isEmpty() || warehouseIds.contains(s.getWarehouseId()))
                .toList();
        Map<Long, Batch> bm = batches.findAllById(rows.stream().map(Stock::getBatchId).distinct().toList()).stream()
                .collect(Collectors.toMap(Batch::getId, Function.identity()));
        List<FefoAllocator.Candidate> candidates = new ArrayList<>();
        for (Stock s : rows) {
            Batch b = bm.get(s.getBatchId());
            if (b != null && b.getStatus() == BatchStatus.RELEASED) {
                candidates.add(new FefoAllocator.Candidate(b.getId(), b.getBatchNo(), s.getWarehouseId(), s.getQty(),
                        b.getMfgDate(), b.getExpDate()));
            }
        }
        return FefoAllocator.allocate(candidates, qty, minRatio, time.today());
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public Batch batch(Long id) {
        return batches.findById(id).orElseThrow(() -> BizException.notFound("批次"));
    }

    @Transactional(readOnly = true)
    public Batch batchByNo(String batchNo) {
        return batches.findByBatchNo(batchNo == null ? "" : batchNo.trim())
                .orElseThrow(() -> BizException.notFound("批号 " + batchNo + " "));
    }

    @Transactional(readOnly = true)
    public BigDecimal onHand(Long batchId) {
        return stocks.findByBatchId(batchId).stream().map(Stock::getQty).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Transactional(readOnly = true)
    public List<Stock> stockOfBatch(Long batchId) {
        return stocks.findByBatchId(batchId);
    }

    @Transactional(readOnly = true)
    public List<StockRow> listStock(ItemType type, Long warehouseId, String q, boolean includeZero) {
        List<Stock> rows = includeZero ? stocks.findAll() : stocks.findByQtyGreaterThan(BigDecimal.ZERO);
        Map<Long, Batch> bm = batches.findAllById(rows.stream().map(Stock::getBatchId).distinct().toList()).stream()
                .collect(Collectors.toMap(Batch::getId, Function.identity()));
        Map<Long, Item> im = lookup.itemMap();
        Map<Long, Warehouse> wm = lookup.warehouseMap();
        LocalDate today = time.today();
        String needle = q == null ? "" : q.trim().toLowerCase();
        return rows.stream()
                .filter(s -> warehouseId == null || warehouseId.equals(s.getWarehouseId()))
                .map(s -> {
                    Batch b = bm.get(s.getBatchId());
                    Item i = im.get(s.getItemId());
                    Warehouse w = wm.get(s.getWarehouseId());
                    return new StockRow(s.getId(), b.getId(), b.getBatchNo(), i.getId(), i.getCode(), i.getName(),
                            i.getItemType().name(), i.getUnit(), w.getId(), w.getName(), s.getQty(), b.getStatus(),
                            b.getStatus().getLabel(), b.getMfgDate(), b.getExpDate(),
                            ShelfLife.remainingRatio(b.getMfgDate(), b.getExpDate(), today));
                })
                .filter(r -> type == null || type.name().equals(r.itemType()))
                .filter(r -> needle.isEmpty() || (r.itemCode() + r.itemName() + r.batchNo()).toLowerCase().contains(needle))
                .sorted(Comparator.comparing(StockRow::itemType).thenComparing(StockRow::itemCode)
                        .thenComparing(StockRow::expDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(StockRow::batchNo))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BatchView> listBatches(Collection<BatchStatus> statuses, String q) {
        List<Batch> list = statuses == null || statuses.isEmpty()
                ? batches.findTop500ByOrderByIdDesc()
                : batches.findByStatusInOrderByIdDesc(statuses);
        String needle = q == null ? "" : q.trim().toLowerCase();
        List<BatchView> views = toViews(list);
        if (needle.isEmpty()) {
            return views;
        }
        return views.stream()
                .filter(v -> (v.batchNo() + v.itemCode() + v.itemName()).toLowerCase().contains(needle))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BatchView> toViews(List<Batch> list) {
        Map<Long, Item> im = lookup.itemMap();
        Map<Long, Partner> pm = lookup.partnerMap();
        Map<Long, BigDecimal> onHand = new HashMap<>();
        if (!list.isEmpty()) {
            for (Stock s : stocks.findByBatchIdIn(list.stream().map(Batch::getId).toList())) {
                onHand.merge(s.getBatchId(), s.getQty(), BigDecimal::add);
            }
        }
        LocalDate today = time.today();
        return list.stream().map(b -> toView(b, im, pm, onHand.getOrDefault(b.getId(), BigDecimal.ZERO), today)).toList();
    }

    public BatchView toView(Batch b, Map<Long, Item> im, Map<Long, Partner> pm, BigDecimal onHand, LocalDate today) {
        Item i = im.get(b.getItemId());
        Partner p = b.getPartnerId() == null ? null : pm.get(b.getPartnerId());
        return new BatchView(b.getId(), b.getBatchNo(), i.getId(), i.getCode(), i.getName(), i.getItemType().name(),
                i.getUnit(), b.getMfgDate(), b.getExpDate(),
                ShelfLife.remainingRatio(b.getMfgDate(), b.getExpDate(), today), b.getStatus(),
                b.getStatus().getLabel(), b.getSourceType(), b.getSourceNo(), b.getPartnerId(),
                p == null ? null : p.getName(), b.getExtBatchNo(), b.getQtyIn(), onHand, b.isOemReleased(),
                b.getFreezeReason(), b.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<TxnView> txnsOfBatch(Long batchId) {
        Map<Long, Warehouse> wm = lookup.warehouseMap();
        return txns.findByBatchIdOrderByIdDesc(batchId).stream()
                .map(t -> new TxnView(t.getId(), wm.get(t.getWarehouseId()).getName(), t.getQtyDelta(),
                        t.getTxnType().getLabel(), t.getRefNo(), t.getOperator(), t.getCreatedAt()))
                .toList();
    }
}
