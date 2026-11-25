package com.cosmetic.scm.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.InvDocType;
import com.cosmetic.scm.domain.StatementStatus;
import com.cosmetic.scm.inventory.InvDoc;
import com.cosmetic.scm.inventory.InvDocLine;
import com.cosmetic.scm.inventory.InvDocLineRepository;
import com.cosmetic.scm.inventory.InvDocRepository;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.MasterService;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.sales.SalesOrder;
import com.cosmetic.scm.sales.SalesOrderRepository;
import com.cosmetic.scm.sales.SoLine;
import com.cosmetic.scm.sales.SoLineRepository;
import com.cosmetic.scm.settlement.CustomerStatement;
import com.cosmetic.scm.settlement.CustomerStatementRepository;
import com.cosmetic.scm.settlement.SupplierStatement;
import com.cosmetic.scm.settlement.SupplierStatementRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 财务经营分析服务（V45）：
 * 1) 成品与客户双维度毛利分析（优先采用批次实际单位成本，缺省回落标准成本）；
 * 2) 基于 AP/AR 到期日的未来现金流预测；
 * 3) 库存盘点盘盈/盘亏与报废损益金额估值。
 * 所有接口仅限有价格权限的岗位访问。
 */
@Service
@RequiredArgsConstructor
public class FinanceInsightsService {

    private final SalesOrderRepository salesOrders;
    private final SoLineRepository soLines;
    private final BatchCostingService batchCosting;
    private final SupplierStatementRepository apRepo;
    private final CustomerStatementRepository arRepo;
    private final InvDocRepository invDocs;
    private final InvDocLineRepository invDocLines;
    private final MasterLookup lookup;
    private final MasterService master;
    private final TimeProvider time;

    public record MarginRow(String code, String name, BigDecimal qty, BigDecimal revenue,
                            BigDecimal cogs, BigDecimal grossProfit, BigDecimal grossMarginPct) {
    }

    public record MarginReport(LocalDate from, LocalDate to, BigDecimal totalRevenue, BigDecimal totalCogs,
                               BigDecimal totalGrossProfit, BigDecimal grossMarginPct,
                               List<MarginRow> byItem, List<MarginRow> byCustomer) {
    }

    public record CashFlowBucket(String bucket, String label, BigDecimal inflow, BigDecimal outflow,
                                 BigDecimal netCashFlow, int arCount, int apCount) {
    }

    public record CashFlowReport(LocalDate asOf, BigDecimal totalInflow, BigDecimal totalOutflow,
                                 BigDecimal netCashFlow, List<CashFlowBucket> buckets) {
    }

    public record VarianceRow(String docNo, String docType, String docTypeLabel, LocalDateTime createdAt,
                              String itemCode, String itemName, String unit, BigDecimal qty,
                              BigDecimal unitCost, BigDecimal amount, String reason) {
    }

    public record VarianceReport(LocalDate from, LocalDate to, BigDecimal gainValue, BigDecimal lossValue,
                                 BigDecimal scrapValue, BigDecimal netValue, List<VarianceRow> rows) {
    }

    private static final class MarginAcc {
        final String code;
        final String name;
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal cogs = BigDecimal.ZERO;

        MarginAcc(String code, String name) {
            this.code = code;
            this.name = name;
        }

        void add(BigDecimal q, BigDecimal rev, BigDecimal cost) {
            qty = qty.add(q);
            revenue = revenue.add(rev);
            cogs = cogs.add(cost);
        }

        MarginRow toRow() {
            BigDecimal gp = revenue.subtract(cogs);
            BigDecimal pct = revenue.signum() == 0 ? null
                    : gp.multiply(BigDecimal.valueOf(100)).divide(revenue, 1, RoundingMode.HALF_UP);
            return new MarginRow(code, name, qty, revenue, cogs, gp, pct);
        }
    }

    @Transactional(readOnly = true)
    public MarginReport margins(LocalDate from, LocalDate to) {
        LocalDate today = time.today();
        LocalDate start = from != null ? from : today.minusDays(180);
        LocalDate end = to != null ? to : today;

        List<SalesOrder> sos = salesOrders.findByOrderDateBetweenOrderByOrderDateAscIdAsc(start, end);
        if (sos.isEmpty()) {
            return new MarginReport(start, end, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, List.of(), List.of());
        }
        Map<Long, SalesOrder> soMap = sos.stream().collect(Collectors.toMap(SalesOrder::getId, o -> o));
        List<SoLine> lines = soLines.findBySoIdIn(soMap.keySet());

        Map<Long, BigDecimal> unitCosts = new HashMap<>(master.unitCosts());
        Map<String, Long> fgCodeToId = lookup.itemMap().values().stream()
                .collect(Collectors.toMap(Item::getCode, Item::getId, (a, b) -> a));
        // 优先使用最近一笔已完工批次的实际单位成本
        for (var bc : batchCosting.report()) {
            Long id = fgCodeToId.get(bc.fgCode());
            if (id != null && bc.actualUnitCost() != null && bc.actualUnitCost().signum() > 0) {
                unitCosts.putIfAbsent(id, bc.actualUnitCost());
            }
        }
        Map<Long, Item> items = lookup.itemMap();
        Map<Long, Partner> partners = lookup.partnerMap();

        Map<Long, MarginAcc> byItem = new LinkedHashMap<>();
        Map<Long, MarginAcc> byCustomer = new LinkedHashMap<>();
        BigDecimal totalRev = BigDecimal.ZERO;
        BigDecimal totalCogs = BigDecimal.ZERO;

        for (SoLine l : lines) {
            if (l.getUnitPrice() == null || l.getUnitPrice().signum() <= 0) {
                continue;
            }
            SalesOrder so = soMap.get(l.getSoId());
            Item fg = items.get(l.getItemId());
            Partner cus = partners.get(so.getCustomerId());
            BigDecimal rev = l.getQty().multiply(l.getUnitPrice()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal uc = unitCosts.getOrDefault(l.getItemId(), BigDecimal.ZERO);
            BigDecimal cost = l.getQty().multiply(uc).setScale(2, RoundingMode.HALF_UP);

            byItem.computeIfAbsent(fg.getId(), k -> new MarginAcc(fg.getCode(), fg.getName())).add(l.getQty(), rev, cost);
            byCustomer.computeIfAbsent(cus.getId(), k -> new MarginAcc(cus.getCode(), cus.getName())).add(l.getQty(), rev, cost);
            totalRev = totalRev.add(rev);
            totalCogs = totalCogs.add(cost);
        }

        List<MarginRow> itemRows = byItem.values().stream().map(MarginAcc::toRow)
                .sorted(Comparator.comparing(MarginRow::revenue, Comparator.reverseOrder())).toList();
        List<MarginRow> cusRows = byCustomer.values().stream().map(MarginAcc::toRow)
                .sorted(Comparator.comparing(MarginRow::revenue, Comparator.reverseOrder())).toList();
        BigDecimal gp = totalRev.subtract(totalCogs);
        BigDecimal pct = totalRev.signum() == 0 ? null
                : gp.multiply(BigDecimal.valueOf(100)).divide(totalRev, 1, RoundingMode.HALF_UP);
        return new MarginReport(start, end, totalRev, totalCogs, gp, pct, itemRows, cusRows);
    }

    @Transactional(readOnly = true)
    public CashFlowReport cashFlowForecast() {
        LocalDate today = time.today();
        String[][] defs = {
                {"OVERDUE", "已逾期"},
                {"NEXT_7D", "未来 7 天"},
                {"DAYS_8_30", "8–30 天"},
                {"DAYS_31_60", "31–60 天"},
                {"OVER_60D", "60 天以上"}
        };
        Map<String, BigDecimal[]> sums = new LinkedHashMap<>();
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (String[] d : defs) {
            sums.put(d[0], new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            counts.put(d[0], new int[]{0, 0});
        }

        for (CustomerStatement ar : arRepo.findByStatusInOrderById(List.of("APPROVED"))) {
            BigDecimal open = ar.outstandingAmount();
            if (open.signum() <= 0) {
                continue;
            }
            String b = bucketOf(ar.getDueDate(), today);
            sums.get(b)[0] = sums.get(b)[0].add(open);
            counts.get(b)[0]++;
        }
        for (SupplierStatement ap : apRepo.findByStatusInOrderById(List.of(StatementStatus.APPROVED))) {
            BigDecimal open = ap.outstandingAmount();
            if (open.signum() == 0) {
                continue;
            }
            String b = bucketOf(ap.effectiveDueDate(), today);
            if (open.signum() > 0) {
                sums.get(b)[1] = sums.get(b)[1].add(open);
            } else {
                // 红字供应商退款视为现金流入
                sums.get(b)[0] = sums.get(b)[0].add(open.abs());
            }
            counts.get(b)[1]++;
        }

        List<CashFlowBucket> buckets = new ArrayList<>();
        BigDecimal totalIn = BigDecimal.ZERO;
        BigDecimal totalOut = BigDecimal.ZERO;
        for (String[] d : defs) {
            BigDecimal in = sums.get(d[0])[0];
            BigDecimal out = sums.get(d[0])[1];
            int[] cnt = counts.get(d[0]);
            buckets.add(new CashFlowBucket(d[0], d[1], in, out, in.subtract(out), cnt[0], cnt[1]));
            totalIn = totalIn.add(in);
            totalOut = totalOut.add(out);
        }
        return new CashFlowReport(today, totalIn, totalOut, totalIn.subtract(totalOut), buckets);
    }

    @Transactional(readOnly = true)
    public VarianceReport inventoryVariance(LocalDate from, LocalDate to) {
        LocalDate today = time.today();
        LocalDate start = from != null ? from : today.minusDays(180);
        LocalDate end = to != null ? to : today;
        LocalDateTime startAt = start.atStartOfDay();
        LocalDateTime endBefore = end.plusDays(1).atStartOfDay();

        List<InvDoc> docs = invDocs.findByDocTypeInAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                        List.of(InvDocType.ADJUST, InvDocType.SCRAP), startAt, endBefore).stream()
                .sorted(Comparator.comparing(InvDoc::getCreatedAt).reversed())
                .toList();
        if (docs.isEmpty()) {
            return new VarianceReport(start, end, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of());
        }
        Map<Long, InvDoc> docMap = docs.stream().collect(Collectors.toMap(InvDoc::getId, d -> d));
        Map<Long, List<InvDocLine>> lineMap = invDocLines.findByDocIdIn(docMap.keySet()).stream()
                .collect(Collectors.groupingBy(InvDocLine::getDocId));
        Map<Long, Item> items = lookup.itemMap();
        Map<Long, BigDecimal> costs = master.unitCosts();

        List<VarianceRow> rows = new ArrayList<>();
        BigDecimal gain = BigDecimal.ZERO;
        BigDecimal loss = BigDecimal.ZERO;
        BigDecimal scrap = BigDecimal.ZERO;

        for (InvDoc d : docs) {
            for (InvDocLine l : lineMap.getOrDefault(d.getId(), List.of())) {
                Item item = items.get(l.getItemId());
                BigDecimal uc = costs.getOrDefault(l.getItemId(), BigDecimal.ZERO);
                BigDecimal signedQty = d.getDocType() == InvDocType.SCRAP ? l.getQty().abs().negate() : l.getQty();
                BigDecimal amt = signedQty.multiply(uc).setScale(2, RoundingMode.HALF_UP);
                if (d.getDocType() == InvDocType.SCRAP) {
                    scrap = scrap.add(amt.abs());
                } else if (amt.signum() >= 0) {
                    gain = gain.add(amt);
                } else {
                    loss = loss.add(amt.abs());
                }
                rows.add(new VarianceRow(d.getDocNo(), d.getDocType().name(), d.getDocType().getLabel(),
                        d.getCreatedAt(), item.getCode(), item.getName(), item.getUnit(),
                        signedQty, uc, amt, d.getReason()));
            }
        }
        BigDecimal net = gain.subtract(loss).subtract(scrap);
        return new VarianceReport(start, end, gain, loss, scrap, net, rows);
    }

    private static String bucketOf(LocalDate dueDate, LocalDate today) {
        if (dueDate == null) return "OVER_60D";
        long days = ChronoUnit.DAYS.between(today, dueDate);
        if (days < 0) return "OVERDUE";
        if (days <= 7) return "NEXT_7D";
        if (days <= 30) return "DAYS_8_30";
        if (days <= 60) return "DAYS_31_60";
        return "OVER_60D";
    }
}
