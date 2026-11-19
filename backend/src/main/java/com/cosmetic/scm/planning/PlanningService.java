package com.cosmetic.scm.planning;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.MoStatus;
import com.cosmetic.scm.domain.MoType;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.PoStatus;
import com.cosmetic.scm.domain.SupplyMode;
import com.cosmetic.scm.domain.WarehouseKind;
import com.cosmetic.scm.domain.rules.DemandForecast;
import com.cosmetic.scm.domain.rules.Replenishment;
import com.cosmetic.scm.domain.rules.ShelfLife;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.inventory.Stock;
import com.cosmetic.scm.inventory.StockRepository;
import com.cosmetic.scm.master.BomLine;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.MasterService;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.Warehouse;
import com.cosmetic.scm.production.MfgOrder;
import com.cosmetic.scm.production.MfgOrderRepository;
import com.cosmetic.scm.purchase.PoLine;
import com.cosmetic.scm.purchase.PoLineRepository;
import com.cosmetic.scm.purchase.PurchaseOrder;
import com.cosmetic.scm.purchase.PurchaseOrderRepository;
import com.cosmetic.scm.sales.SalesOrder;
import com.cosmetic.scm.sales.SalesOrderRepository;
import com.cosmetic.scm.sales.SoLine;
import com.cosmetic.scm.sales.SoLineRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 补货建议与需求预测（只读计算，不写库）。
 * 成品：按近 N 天出库算日均需求，用再订货点法给出建议生产 / 委托量（{@link Replenishment}）。
 * 物料：把计划生产量按 BOM 展开成品牌供料需求，加上已计划未发料的生产单需求，
 * 扣掉在库已放行、待检和未到货采购，得出净采购量，并带出上次采购的供应商。
 * 页面据此一键生成生产单 / 采购单草稿，走的仍是原有接口和全部校验。
 */
@Service
@RequiredArgsConstructor
public class PlanningService {

    /** 成品默认按 100 件取整；包材按 1 个取整；原料保留 4 位小数。 */
    private static final BigDecimal FG_LOT = new BigDecimal("100");
    private static final double NEAR_EXPIRY = 0.5;
    private static final int FORECAST_MONTHS = 3;

    private final SalesOrderRepository salesOrders;
    private final SoLineRepository soLines;
    private final StockRepository stocks;
    private final BatchRepository batches;
    private final MfgOrderRepository mfgOrders;
    private final PurchaseOrderRepository purchaseOrders;
    private final PoLineRepository poLines;
    private final MasterService master;
    private final com.cosmetic.scm.sourcing.SupplierPriceRepository supplierPrices;
    private final com.cosmetic.scm.salesreturn.SalesReturnRepository salesReturns;
    private final com.cosmetic.scm.salesreturn.SalesReturnLineRepository returnLines;
    private final MasterLookup lookup;
    private final TimeProvider time;

    /** useForecast：按未来 3 个月的预测算日均，而不是观察期的历史平均。 */
    public record PlanParams(@Min(7) @Max(365) int windowDays, @Min(0) @Max(180) int leadDays,
                             @Min(0) @Max(180) int safetyDays, @Min(0) @Max(365) int coverDays, boolean useForecast) {

        public PlanParams(int windowDays, int leadDays, int safetyDays, int coverDays) {
            this(windowDays, leadDays, safetyDays, coverDays, false);
        }
    }

    public record MonthQty(String month, BigDecimal qty) {
    }

    public record FgRow(Long itemId, String code, String name, String unit, BigDecimal shipped, List<MonthQty> monthly,
                        BigDecimal dailyDemand, BigDecimal available, BigDecimal nearExpiry, BigDecimal pendingRelease,
                        BigDecimal wip, BigDecimal projected, BigDecimal safetyStock, BigDecimal reorderPoint,
                        Integer daysOfSupply, boolean belowReorderPoint, BigDecimal suggested, Long factoryId,
                        String factoryName, BigDecimal unitCost, BigDecimal listPrice, List<MonthQty> forecast,
                        String forecastMethod, BigDecimal forecastDaily) {
    }

    public record PlanLine(@NotNull Long fgItemId, @NotNull @Positive BigDecimal qty, Long factoryId) {
    }

    public record MaterialReq(@NotEmpty(message = "请至少选择一个成品") @Valid List<PlanLine> lines) {
    }

    public record MaterialRow(Long itemId, String code, String name, String unit, String itemType, BigDecimal gross,
                              BigDecimal openOrders, BigDecimal onHand, BigDecimal quarantine, BigDecimal onOrder,
                              BigDecimal suggested, Long supplierId, String supplierName, boolean supplierUsable,
                              List<String> usedBy, BigDecimal unitCost,
                              BigDecimal contractPrice, BigDecimal contractTaxRate) {
    }

    // ---------- 成品 ----------

    @Transactional(readOnly = true)
    public List<FgRow> finishedGoods(PlanParams params) {
        Replenishment.Params p = new Replenishment.Params(params.windowDays(), params.leadDays(), params.safetyDays(),
                params.coverDays());
        LocalDate today = time.today();
        LocalDate from = today.minusDays(params.windowDays() - 1L);
        YearMonth thisMonth = YearMonth.from(today);

        // 出库：观察期合计 + 按月汇总（预测最多看 36 个月）
        LocalDate historyFrom = thisMonth.minusMonths(DemandForecast.MAX_HISTORY_MONTHS).atDay(1);
        List<SalesOrder> sos = salesOrders.findByOrderDateGreaterThanEqual(from.isBefore(historyFrom) ? from : historyFrom);
        Map<Long, LocalDate> soDate = sos.stream().collect(Collectors.toMap(SalesOrder::getId, SalesOrder::getOrderDate));
        Map<Long, BigDecimal> shipped = new HashMap<>();
        Map<Long, Map<YearMonth, BigDecimal>> monthly = new HashMap<>();
        for (SoLine l : sos.isEmpty() ? List.<SoLine>of() : soLines.findBySoIdIn(soDate.keySet())) {
            LocalDate d = soDate.get(l.getSoId());
            if (!d.isBefore(from)) {
                shipped.merge(l.getItemId(), l.getQty(), BigDecimal::add);
            }
            if (!d.isBefore(historyFrom)) {
                monthly.computeIfAbsent(l.getItemId(), k -> new HashMap<>()).merge(YearMonth.from(d), l.getQty(), BigDecimal::add);
            }
        }

        // 退货不算需求，否则退回的大单会抬高日均、多补货。召回回收的不扣：那批货确实卖掉了，是质量问题，不代表需求少了
        List<com.cosmetic.scm.salesreturn.SalesReturn> rets = salesReturns.findByReturnDateGreaterThanEqual(from.isBefore(historyFrom) ? from : historyFrom)
                .stream().filter(r -> r.getRecallId() == null).toList();
        Map<Long, LocalDate> retDate = rets.stream().collect(Collectors.toMap(com.cosmetic.scm.salesreturn.SalesReturn::getId, com.cosmetic.scm.salesreturn.SalesReturn::getReturnDate));
        Map<Long, BigDecimal> returned = new HashMap<>();
        for (var l : rets.isEmpty() ? List.<com.cosmetic.scm.salesreturn.SalesReturnLine>of() : returnLines.findByReturnIdIn(retDate.keySet())) {
            LocalDate d = retDate.get(l.getReturnId());
            if (!d.isBefore(from)) {
                returned.merge(l.getItemId(), l.getQty(), BigDecimal::add);
            }
            if (!d.isBefore(historyFrom)) {
                monthly.computeIfAbsent(l.getItemId(), k -> new HashMap<>()).merge(YearMonth.from(d), l.getQty().negate(), BigDecimal::add);
            }
        }

        // 库存：自有仓已放行（另算临期部分）、待上市放行
        Map<Long, Warehouse> wm = lookup.warehouseMap();
        List<Stock> rows = stocks.findByQtyGreaterThan(BigDecimal.ZERO);
        Map<Long, Batch> bm = batches.findAllById(rows.stream().map(Stock::getBatchId).distinct().toList()).stream()
                .collect(Collectors.toMap(Batch::getId, Function.identity()));
        Map<Long, BigDecimal> available = new HashMap<>();
        Map<Long, BigDecimal> nearExpiry = new HashMap<>();
        Map<Long, BigDecimal> pending = new HashMap<>();
        for (Stock s : rows) {
            Batch b = bm.get(s.getBatchId());
            WarehouseKind kind = wm.get(s.getWarehouseId()).getKind();
            // 过期的不能再卖，算成可用会少补货
            if (ShelfLife.isExpired(b.getExpDate(), today)) {
                continue;
            }
            if (b.getStatus() == BatchStatus.RELEASED && kind == WarehouseKind.OWN) {
                available.merge(s.getItemId(), s.getQty(), BigDecimal::add);
                Double ratio = ShelfLife.remainingRatio(b.getMfgDate(), b.getExpDate(), today);
                if (ratio != null && ratio < NEAR_EXPIRY) {
                    nearExpiry.merge(s.getItemId(), s.getQty(), BigDecimal::add);
                }
            } else if (b.getStatus() == BatchStatus.PENDING_RELEASE && kind != WarehouseKind.RETURN) {
                pending.merge(s.getItemId(), s.getQty(), BigDecimal::add);
            }
        }

        // 在制：已计划、已发料的生产 / 委托单；默认生产方取最近一次的
        Map<Long, BigDecimal> wip = mfgOrders.findByStatusIn(List.of(MoStatus.PLANNED, MoStatus.ISSUED)).stream()
                .collect(Collectors.toMap(MfgOrder::getFgItemId, MfgOrder::getPlannedQty, BigDecimal::add));
        Map<Long, Long> lastFactory = new HashMap<>();
        for (MfgOrder m : mfgOrders.findTop300ByOrderByIdDesc()) {
            lastFactory.putIfAbsent(m.getFgItemId(), m.getFactoryId());
        }
        Map<Long, Partner> pm = lookup.partnerMap();
        Map<Long, BigDecimal> cost = master.unitCosts();

        List<FgRow> out = new ArrayList<>();
        for (Item fg : lookup.itemMap().values().stream()
                .filter(i -> i.getItemType() == ItemType.FG && i.isActive())
                .sorted((a, b) -> a.getCode().compareTo(b.getCode())).toList()) {
            Long id = fg.getId();
            BigDecimal inPipe = pending.getOrDefault(id, BigDecimal.ZERO).add(wip.getOrDefault(id, BigDecimal.ZERO));
            DemandForecast.Result fc = DemandForecast.forecast(history(monthly.getOrDefault(id, Map.of()), thisMonth), FORECAST_MONTHS);
            BigDecimal fcDaily = fc.next().stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(daysIn(thisMonth, FORECAST_MONTHS)), 4, RoundingMode.HALF_UP);
            // 没有历史时没有预测可用，仍按观察期（结果就是不建议补货）
            Replenishment.Result r = params.useForecast() && fc.method() != DemandForecast.Method.NONE
                    ? Replenishment.calcFromDaily(fcDaily, available.getOrDefault(id, BigDecimal.ZERO), inPipe, p, FG_LOT)
                    : Replenishment.calc(shipped.getOrDefault(id, BigDecimal.ZERO).subtract(returned.getOrDefault(id, BigDecimal.ZERO)).max(BigDecimal.ZERO), available.getOrDefault(id, BigDecimal.ZERO), inPipe, p, FG_LOT);
            List<MonthQty> forecast = new ArrayList<>();
            for (int i = 0; i < fc.next().size(); i++) {
                forecast.add(new MonthQty(thisMonth.plusMonths(i).toString(), fc.next().get(i)));
            }
            List<MonthQty> months = new ArrayList<>();
            for (int i = 2; i >= 0; i--) {
                YearMonth ym = thisMonth.minusMonths(i);
                months.add(new MonthQty(ym.toString(), monthly.getOrDefault(id, Map.of()).getOrDefault(ym, BigDecimal.ZERO).max(BigDecimal.ZERO)));
            }
            Partner f = pm.get(lastFactory.get(id));
            out.add(new FgRow(id, fg.getCode(), fg.getName(), fg.getUnit(), shipped.getOrDefault(id, BigDecimal.ZERO),
                    months, r.dailyDemand(), available.getOrDefault(id, BigDecimal.ZERO),
                    nearExpiry.getOrDefault(id, BigDecimal.ZERO), pending.getOrDefault(id, BigDecimal.ZERO),
                    wip.getOrDefault(id, BigDecimal.ZERO), r.projected(), r.safetyStock(), r.reorderPoint(),
                    r.daysOfSupply(), r.belowReorderPoint(), r.suggested(), f == null ? null : f.getId(),
                    f == null ? null : f.getName(), cost.get(id), fg.getListPrice(), forecast, fc.method().getLabel(), fcDaily));
        }
        return out;
    }

    /** 从首次出库到上个完整自然月的月度出库量（旧 → 新）。当月还没过完，不算。 */
    private static List<BigDecimal> history(Map<YearMonth, BigDecimal> months, YearMonth thisMonth) {
        YearMonth first = months.keySet().stream().filter(m -> m.isBefore(thisMonth)).min(YearMonth::compareTo).orElse(null);
        List<BigDecimal> out = new ArrayList<>();
        if (first != null) {
            for (YearMonth m = first; m.isBefore(thisMonth); m = m.plusMonths(1)) {
                // 退货比当月出库多时算 0，不能是负的需求
                out.add(months.getOrDefault(m, BigDecimal.ZERO).max(BigDecimal.ZERO));
            }
        }
        return out;
    }

    private static long daysIn(YearMonth start, int months) {
        return java.time.temporal.ChronoUnit.DAYS.between(start.atDay(1), start.plusMonths(months).atDay(1));
    }

    // ---------- 物料 ----------

    @Transactional(readOnly = true)
    public List<MaterialRow> materials(MaterialReq req) {
        Map<Long, Item> im = lookup.itemMap();
        Map<Long, Partner> pm = lookup.partnerMap();
        Map<Long, BigDecimal> gross = new LinkedHashMap<>();
        Map<Long, List<String>> usedBy = new HashMap<>();
        for (PlanLine l : req.lines()) {
            Item fg = im.get(l.fgItemId());
            if (fg == null || fg.getItemType() != ItemType.FG) {
                throw new BizException("只能为成品做物料计划");
            }
            boolean own = l.factoryId() != null && pm.get(l.factoryId()) != null
                    && pm.get(l.factoryId()).getKind() == PartnerKind.OWN_FACTORY;
            List<BomLine> bom = master.bomLines(fg.getId());
            if (bom.isEmpty()) {
                throw new BizException(fg.getName() + " 还没有维护 BOM");
            }
            for (BomLine b : bom) {
                if (own || b.getSupplyMode() == SupplyMode.BRAND) {
                    gross.merge(b.getItemId(), b.getQtyPer().multiply(l.qty()).setScale(4, RoundingMode.UP), BigDecimal::add);
                    usedBy.computeIfAbsent(b.getItemId(), k -> new ArrayList<>())
                            .add(fg.getName() + " × " + l.qty().stripTrailingZeros().toPlainString());
                }
            }
        }

        // 已计划未发料的生产单还会占用品牌供料
        Map<Long, BigDecimal> openNeed = new HashMap<>();
        for (MfgOrder m : mfgOrders.findByStatusIn(List.of(MoStatus.PLANNED))) {
            for (BomLine b : master.bomLines(m.getFgItemId())) {
                if (m.getMoType() == MoType.OWN || b.getSupplyMode() == SupplyMode.BRAND) {
                    openNeed.merge(b.getItemId(), b.getQtyPer().multiply(m.getPlannedQty()).setScale(4, RoundingMode.UP),
                            BigDecimal::add);
                }
            }
        }

        // 可用：自有仓已放行 + 待检（收货后很快放行）+ 未到货采购
        Map<Long, Warehouse> wm = lookup.warehouseMap();
        List<Stock> rows = stocks.findByQtyGreaterThan(BigDecimal.ZERO);
        Map<Long, Batch> bm = batches.findAllById(rows.stream().map(Stock::getBatchId).distinct().toList()).stream()
                .collect(Collectors.toMap(Batch::getId, Function.identity()));
        Map<Long, BigDecimal> onHand = new HashMap<>();
        Map<Long, BigDecimal> quarantine = new HashMap<>();
        for (Stock s : rows) {
            if (wm.get(s.getWarehouseId()).getKind() != WarehouseKind.OWN) {
                continue;
            }
            // 过期的不能发料，算成可用会少采购
            if (ShelfLife.isExpired(bm.get(s.getBatchId()).getExpDate(), time.today())) {
                continue;
            }
            BatchStatus st = bm.get(s.getBatchId()).getStatus();
            if (st == BatchStatus.RELEASED) {
                onHand.merge(s.getItemId(), s.getQty(), BigDecimal::add);
            } else if (st == BatchStatus.QUARANTINE) {
                quarantine.merge(s.getItemId(), s.getQty(), BigDecimal::add);
            }
        }
        List<PurchaseOrder> open = purchaseOrders.findByStatusIn(List.of(PoStatus.OPEN, PoStatus.PARTIAL));
        Map<Long, BigDecimal> onOrder = open.isEmpty() ? Map.of() : poLines.findByPoIdIn(ids(open)).stream()
                .collect(Collectors.toMap(PoLine::getItemId, l -> l.getQty().subtract(l.getReceivedQty()).max(BigDecimal.ZERO),
                        BigDecimal::add));

        // 上次采购的供应商
        Map<Long, Long> lastSupplier = new HashMap<>();
        List<PurchaseOrder> recent = purchaseOrders.findTop300ByOrderByIdDesc();
        Map<Long, Long> supplierOfPo = recent.stream().collect(Collectors.toMap(PurchaseOrder::getId, PurchaseOrder::getSupplierId));
        if (!recent.isEmpty()) {
            poLines.findByPoIdIn(supplierOfPo.keySet()).stream()
                    .sorted((a, b) -> b.getPoId().compareTo(a.getPoId()))
                    .forEach(l -> lastSupplier.putIfAbsent(l.getItemId(), supplierOfPo.get(l.getPoId())));
        }
        Map<Long, Boolean> usable = master.listPartners().stream()
                .collect(Collectors.toMap(v -> v.id(), v -> v.usable()));

        // 联动当日有效协议价：优先推荐价格最低且资质可用的协议价供应商（仅价格权限角色可见协议单价）
        LocalDate today = time.today();
        boolean showPrice = com.cosmetic.scm.security.PriceAccess.allowed();
        Map<Long, com.cosmetic.scm.sourcing.SupplierPrice> bestContract = new HashMap<>();
        for (var sp : supplierPrices.findAllByOrderByIdDesc()) {
            if (!sp.isEffectiveOn(today) || !usable.getOrDefault(sp.getSupplierId(), false)) {
                continue;
            }
            bestContract.merge(sp.getItemId(), sp,
                    (a, b) -> b.getUnitPrice().compareTo(a.getUnitPrice()) < 0 ? b : a);
        }

        List<MaterialRow> out = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> e : gross.entrySet()) {
            Item m = im.get(e.getKey());
            BigDecimal need = e.getValue().add(openNeed.getOrDefault(m.getId(), BigDecimal.ZERO));
            BigDecimal have = onHand.getOrDefault(m.getId(), BigDecimal.ZERO)
                    .add(quarantine.getOrDefault(m.getId(), BigDecimal.ZERO))
                    .add(onOrder.getOrDefault(m.getId(), BigDecimal.ZERO));
            BigDecimal lot = m.getItemType() == ItemType.PK ? BigDecimal.ONE : null;
            var cp = bestContract.get(m.getId());
            Long sid = cp != null ? cp.getSupplierId() : lastSupplier.get(m.getId());
            Partner sup = sid == null ? null : pm.get(sid);
            out.add(new MaterialRow(m.getId(), m.getCode(), m.getName(), m.getUnit(), m.getItemType().name(), e.getValue(),
                    openNeed.getOrDefault(m.getId(), BigDecimal.ZERO), onHand.getOrDefault(m.getId(), BigDecimal.ZERO),
                    quarantine.getOrDefault(m.getId(), BigDecimal.ZERO), onOrder.getOrDefault(m.getId(), BigDecimal.ZERO),
                    Replenishment.netRequirement(need, have, lot), sup == null ? null : sup.getId(),
                    sup == null ? null : sup.getName(), sup != null && usable.getOrDefault(sup.getId(), false),
                    usedBy.getOrDefault(m.getId(), List.of()), m.getStdCost(),
                    showPrice && cp != null ? cp.getUnitPrice() : null,
                    showPrice && cp != null ? cp.getTaxRate() : null));
        }
        return out;
    }

    private static Collection<Long> ids(List<PurchaseOrder> list) {
        return list.stream().map(PurchaseOrder::getId).toList();
    }
}
