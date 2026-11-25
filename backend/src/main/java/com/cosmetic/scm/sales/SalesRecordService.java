package com.cosmetic.scm.sales;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.integration.ChannelOrder;
import com.cosmetic.scm.integration.ChannelOrderRepository;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.ItemRepository;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.PartnerRepository;
import com.cosmetic.scm.master.Warehouse;
import com.cosmetic.scm.master.WarehouseRepository;
import com.cosmetic.scm.settlement.CustomerStatementLineRepository;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 产品销售记录（规范第五十八条、检查要点委托生产版第 19 项）：产品名称、注册证或备案编号、使用期限、净含量、数量、销售日期、价格，
 * 购买者名称、地址和联系方式。一行是一张出库单里的一个批号。只读，只依赖仓库接口。
 *
 * <p>购买者和产品信息取出库时记下的快照；V50 之前的出库没有快照，按当前主数据显示。价格取出库单价，出库时没定价的取对账单上的单价。
 * 使用期限取批号的限期使用日期。
 */
@Service
@RequiredArgsConstructor
public class SalesRecordService {

    /** 一次最多查一年，导出也一样。 */
    public static final int MAX_DAYS = 366;

    /** 页面默认查最近多少天。 */
    public static final int DEFAULT_DAYS = 30;

    static final Set<String> PRICE_ROLES = Set.of("ROLE_ADMIN", "ROLE_SALES", "ROLE_FINANCE");

    private final SalesOrderRepository orders;
    private final SoLineRepository lines;
    private final SoAllocationRepository allocations;
    private final BatchRepository batches;
    private final ItemRepository items;
    private final PartnerRepository partners;
    private final WarehouseRepository warehouses;
    private final CustomerStatementLineRepository statementLines;
    private final ChannelOrderRepository channelOrders;
    private final TimeProvider time;

    /**
     * unitPrice、amount 没有权限时为空；priceSource：出库单 / 对账单 / 空。gaps：这一行缺的内容（有没有价格对没权限的人也显示，不显示金额）。
     */
    public record RecordRow(Long soId, String soNo, LocalDate saleDate, String channel, String platformOrderNo, String warehouseName,
                            Long itemId, String itemCode, String itemName, String filingNo, String batchNo, LocalDate expDate, String netContent,
                            BigDecimal qty, String unit, BigDecimal unitPrice, BigDecimal amount, String priceSource,
                            String buyerName, String buyerAddress, String buyerContact, List<String> gaps) {
        public boolean complete() {
            return gaps.isEmpty();
        }
    }

    public record Records(LocalDate from, LocalDate to, boolean priceVisible, int orders, int incompleteRows, List<String> gapSummary,
                          List<RecordRow> rows) {
    }

    /** 标签净含量优先，没有就按净含量（g）。 */
    public static String netContentOf(Item i) {
        if (i.getNetLabel() != null && !i.getNetLabel().isBlank()) {
            return i.getNetLabel().trim();
        }
        return i.getNetG() == null ? null : i.getNetG().stripTrailingZeros().toPlainString() + " g";
    }

    public static boolean priceAllowed() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> PRICE_ROLES.contains(a.getAuthority()));
    }

    /** 不传日期默认最近 30 天；区间最长一年。 */
    @Transactional(readOnly = true)
    public Records records(LocalDate from, LocalDate to) {
        LocalDate today = time.today();
        LocalDate t = to == null ? today : to;
        LocalDate f = from == null ? t.minusDays(DEFAULT_DAYS - 1) : from;
        if (f.isAfter(t)) {
            throw new BizException("开始日期不能晚于结束日期");
        }
        if (f.plusDays(MAX_DAYS).isBefore(t.plusDays(1))) {
            throw new BizException("一次最多查一年的销售记录，请缩小日期范围");
        }
        List<SalesOrder> sos = orders.findByOrderDateBetweenOrderByOrderDateAscIdAsc(f, t);
        boolean showPrice = priceAllowed();
        List<RecordRow> rows = rowsOf(sos, showPrice);
        Map<String, Integer> summary = new LinkedHashMap<>();
        rows.forEach(r -> r.gaps().forEach(g -> summary.merge(g, 1, Integer::sum)));
        return new Records(f, t, showPrice, sos.size(), (int) rows.stream().filter(r -> !r.complete()).count(),
                summary.entrySet().stream().map(e -> e.getKey() + " " + e.getValue() + " 行").toList(), rows);
    }

    /** 每张出库单缺的内容（出库单列表和刚出库时提示用），没有缺的不在结果里。 */
    @Transactional(readOnly = true)
    public Map<Long, List<String>> gapsByOrder(List<SalesOrder> sos) {
        Map<Long, Set<String>> m = new LinkedHashMap<>();
        rowsOf(sos, false).forEach(r -> r.gaps().forEach(g -> m.computeIfAbsent(r.soId(), k -> new LinkedHashSet<>()).add(g)));
        Map<Long, List<String>> out = new HashMap<>();
        m.forEach((k, v) -> out.put(k, List.copyOf(v)));
        return out;
    }

    private List<RecordRow> rowsOf(List<SalesOrder> sos, boolean showPrice) {
        if (sos.isEmpty()) {
            return List.of();
        }
        List<Long> soIds = sos.stream().map(SalesOrder::getId).toList();
        List<SoLine> soLines = lines.findBySoIdIn(soIds);
        List<SoAllocation> allocs = allocations.findBySoIdIn(soIds);
        Map<Long, Batch> batchMap = byId(batches.findAllById(ids(allocs.stream().map(SoAllocation::getBatchId).toList())), Batch::getId);
        Map<Long, Item> itemMap = byId(items.findAllById(ids(soLines.stream().map(SoLine::getItemId).toList())), Item::getId);
        Map<Long, Partner> partnerMap = byId(partners.findAllById(ids(sos.stream().map(SalesOrder::getCustomerId).toList())), Partner::getId);
        Map<Long, Warehouse> whMap = byId(warehouses.findAllById(ids(sos.stream().map(SalesOrder::getWarehouseId).toList())), Warehouse::getId);
        Map<Long, String> platformNo = new HashMap<>();
        channelOrders.findBySoIdIn(soIds).forEach(c -> platformNo.put(c.getSoId(), platformRef(c)));
        Map<Long, BigDecimal> billed = new HashMap<>();
        List<Long> unpriced = soLines.stream().filter(l -> l.getUnitPrice() == null).map(SoLine::getId).toList();
        if (!unpriced.isEmpty()) {
            statementLines.billedPrices(unpriced).forEach(r -> billed.putIfAbsent((Long) r[0], (BigDecimal) r[1]));
        }
        // 一张出库单同一个成品只有一行（出库时合并），按 出库单 + 成品 找到行
        Map<String, SoLine> lineOf = new HashMap<>();
        soLines.forEach(l -> lineOf.putIfAbsent(l.getSoId() + ":" + l.getItemId(), l));
        Map<Long, List<SoAllocation>> allocBySo = allocs.stream().collect(Collectors.groupingBy(SoAllocation::getSoId));

        List<RecordRow> out = new ArrayList<>();
        for (SalesOrder so : sos) {
            Partner p = partnerMap.get(so.getCustomerId());
            String buyerName = so.getBuyerName() != null ? so.getBuyerName() : p == null ? null : p.getName();
            String buyerAddress = so.getBuyerName() != null ? so.getBuyerAddress() : p == null ? null : p.getAddress();
            String buyerContact = so.getBuyerName() != null ? so.getBuyerContact() : p == null ? null : p.getContact();
            Warehouse w = whMap.get(so.getWarehouseId());
            for (SoAllocation a : allocBySo.getOrDefault(so.getId(), List.of())) {
                Item item = itemMap.get(a.getItemId());
                Batch b = batchMap.get(a.getBatchId());
                SoLine l = lineOf.get(so.getId() + ":" + a.getItemId());
                boolean snap = l != null && (l.getFilingNo() != null || l.getNetContent() != null);
                String filingNo = snap ? l.getFilingNo() : item == null ? null : item.getFilingNo();
                String net = snap ? l.getNetContent() : item == null ? null : netContentOf(item);
                BigDecimal price = l == null ? null : l.getUnitPrice();
                String source = price != null ? "出库单" : null;
                if (price == null && l != null && billed.containsKey(l.getId())) {
                    price = billed.get(l.getId());
                    source = "对账单";
                }
                List<String> gaps = new ArrayList<>();
                if (blank(filingNo)) {
                    gaps.add("没有注册证或备案编号");
                }
                if (b == null || b.getExpDate() == null) {
                    gaps.add("批号没有限期使用日期");
                }
                if (blank(net)) {
                    gaps.add("没有净含量");
                }
                if (price == null) {
                    gaps.add("没有价格");
                }
                if (blank(buyerAddress)) {
                    gaps.add("购买者没有地址");
                }
                if (blank(buyerContact)) {
                    gaps.add("购买者没有联系方式");
                }
                BigDecimal shown = showPrice ? price : null;
                out.add(new RecordRow(so.getId(), so.getSoNo(), so.getOrderDate(), p == null ? null : p.getChannel(), platformNo.get(so.getId()),
                        w == null ? null : w.getName(), a.getItemId(), item == null ? null : item.getCode(), item == null ? null : item.getName(),
                        filingNo, b == null ? null : b.getBatchNo(), b == null ? null : b.getExpDate(), net, a.getQty(), item == null ? null : item.getUnit(),
                        shown, shown == null ? null : a.getQty().multiply(shown).setScale(2, RoundingMode.HALF_UP), showPrice ? source : null,
                        buyerName, buyerAddress, buyerContact, List.copyOf(gaps)));
            }
        }
        return out;
    }

    private static String platformRef(ChannelOrder c) {
        return c.getPlatform() + " " + c.getPlatformOrderNo();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static List<Long> ids(Collection<Long> c) {
        return c.stream().filter(java.util.Objects::nonNull).distinct().toList();
    }

    private static <T> Map<Long, T> byId(List<T> list, Function<T, Long> id) {
        return list.stream().collect(Collectors.toMap(id, Function.identity(), (x, y) -> x));
    }
}
