package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.cosmetic.scm.domain.MoStatus;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.SupplyMode;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.inventory.InventoryService;
import com.cosmetic.scm.inventory.StockRepository;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.ItemRepository;
import com.cosmetic.scm.master.MasterService;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.PartnerRepository;
import com.cosmetic.scm.production.MfgOrderRepository;
import com.cosmetic.scm.production.ProductionService;
import com.cosmetic.scm.purchase.PurchaseService;
import com.cosmetic.scm.qms.RetainSampleRepository;
import com.cosmetic.scm.qms.RetainService;
import com.cosmetic.scm.sales.SalesService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * 同一操作同时发多次：按钮连点、网络重试都会造成。业务结果必须只生效一次。
 * 这些用例不能回滚，用独立的内存库，不影响其它测试类依赖的示例数据。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-race;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000")
class ConcurrencyIntegrationTest {

    private static final int THREADS = 8;

    @Autowired ProductionService production;
    @Autowired PurchaseService purchases;
    @Autowired RetainService retains;
    @Autowired RetainSampleRepository samples;
    @Autowired SalesService sales;
    @Autowired InventoryService inventory;
    @Autowired StockRepository stocks;
    @Autowired BatchRepository batches;
    @Autowired MfgOrderRepository orders;
    @Autowired ItemRepository items;
    @Autowired PartnerRepository partners;
    @Autowired MasterService master;
    @Autowired com.cosmetic.scm.master.MasterImportService imports;

    /** 同时跑 THREADS 次，返回成功次数；业务拒绝和并发冲突都算失败，其它异常直接让测试失败。 */
    private int race(String user, java.util.function.IntFunction<Object> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            int n = i;
            Callable<Boolean> task = () -> {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
                start.await();
                try {
                    action.apply(n);
                    return true;
                } catch (com.cosmetic.scm.common.BizException | org.springframework.dao.ConcurrencyFailureException e) {
                    return false;
                } finally {
                    SecurityContextHolder.clearContext();
                }
            };
            results.add(pool.submit(task));
        }
        start.countDown();
        int ok = 0;
        for (Future<Boolean> f : results) {
            if (f.get()) {
                ok++;
            }
        }
        pool.shutdown();
        return ok;
    }

    private Item item(String code) {
        return items.findAll().stream().filter(i -> code.equals(i.getCode())).findFirst().orElseThrow();
    }

    private Partner oem() {
        return partners.findAll().stream().filter(p -> p.getKind() == PartnerKind.OEM).findFirst().orElseThrow();
    }

    private Long mainWarehouse() {
        SecurityContextHolder.clearContext();
        return master.listWarehouses().stream().filter(w -> "WH-MAIN".equals(w.code())).findFirst().orElseThrow().id();
    }

    private ProductionService.MoView newOrder() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("prod1", null, List.of()));
        try {
            return production.create(new ProductionService.CreateMoReq(oem().getId(), item("FG-SER-001").getId(), BigDecimal.TEN, null));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private BigDecimal onHand(String code) {
        Long id = item(code).getId();
        return stocks.findAll().stream().filter(s -> s.getItemId().equals(id)).map(s -> s.getQty()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void as(String user, Runnable r) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        try {
            r.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void bomImportDryRunWritesNothingAndValidApplies() {
        // 这条要真实的独立事务才能验证回滚，所以放在不套事务的这个类里
        as("admin", () -> {
            Item fg = master.createItem(new com.cosmetic.scm.master.MasterDtos.ItemReq("FG-DRY", "预检成品", com.cosmetic.scm.domain.ItemType.FG, "瓶", null, null,
                    false, 36, null, new BigDecimal("30"), true, null, null));
            var rows = List.of(new com.cosmetic.scm.master.MasterImportService.BomImportRow("FG-DRY", "RM-GLY-002", new BigDecimal("0.001"), SupplyMode.BRAND));
            var dry = imports.importBoms(new com.cosmetic.scm.master.MasterImportService.BomImportReq(rows, true));
            assertThat(dry.errors()).isEmpty();
            assertThat(dry.created()).isEqualTo(1);
            assertThat(dry.applied()).isFalse();
            assertThat(master.bomLines(fg.getId())).isEmpty();
            // 预检之后真的导入，不会被刚才那次判成“已有配方”
            assertThat(imports.importBoms(new com.cosmetic.scm.master.MasterImportService.BomImportReq(rows, false)).applied()).isTrue();
            assertThat(master.bomLines(fg.getId())).hasSize(1);
        });
    }

    @Test
    void completingTwiceCreatesOneBatch() throws Exception {
        var mo = newOrder();
        as("prod1", () -> production.issue(mo.id()));
        var ext = production.detail(mo.id()).requirements().stream().filter(r -> r.supplyMode() == SupplyMode.OEM)
                .map(r -> new ProductionService.ExtMaterialReq(r.itemId(), "EXT", r.requiredQty())).toList();
        Long wh = mainWarehouse();
        long before = batches.count();
        int ok = race("prod1", i -> production.complete(mo.id(), new ProductionService.CompleteReq(BigDecimal.TEN, LocalDate.now(), null, wh, true, null, ext)));
        assertThat(ok).isEqualTo(1);
        assertThat(batches.count()).isEqualTo(before + 1);
    }

    @Test
    void cancellingTwiceReturnsMaterialsOnce() throws Exception {
        var mo = newOrder();
        as("prod1", () -> production.issue(mo.id()));
        BigDecimal mid = onHand("PK-BTL-001");
        int ok = race("prod1", i -> production.cancel(mo.id(), new ProductionService.CancelReq("重复点击")));
        assertThat(ok).isEqualTo(1);
        assertThat(onHand("PK-BTL-001")).isEqualByComparingTo(mid.add(BigDecimal.TEN));
    }

    @Test
    void issueAndCancelTogetherLeaveConsistentStock() throws Exception {
        var mo = newOrder();
        BigDecimal before = onHand("PK-BTL-001");
        race("prod1", i -> i % 2 == 0 ? production.issue(mo.id()) : production.cancel(mo.id(), new ProductionService.CancelReq("同时")));
        MoStatus status = orders.findById(mo.id()).orElseThrow().getStatus();
        BigDecimal expected = status == MoStatus.ISSUED ? before.subtract(BigDecimal.TEN) : before;
        assertThat(onHand("PK-BTL-001")).isEqualByComparingTo(expected);
    }

    @Test
    void receivingTwiceCannotExceedOrderQty() throws Exception {
        Partner sup = partners.findAll().stream().filter(p -> p.getKind() == PartnerKind.SUPPLIER && p.isQualified()).findFirst().orElseThrow();
        Long rm = item("RM-GLY-002").getId();
        var po = new PurchaseService.PoView[1];
        as("purchase1", () -> po[0] = purchases.create(new PurchaseService.CreatePoReq(sup.getId(), List.of(new PurchaseService.PoLineReq(rm, BigDecimal.TEN)))));
        Long line = po[0].lines().get(0).id();
        Long wh = mainWarehouse();
        long before = batches.count();
        int ok = race("purchase1", i -> purchases.receive(po[0].id(), new PurchaseService.ReceiveReq(List.of(
                new PurchaseService.ReceiveLineReq(line, BigDecimal.TEN, "RACE-" + i, LocalDate.now().minusDays(1), null, wh)))));
        assertThat(ok).isEqualTo(1);
        assertThat(batches.count()).isEqualTo(before + 1);
        assertThat(purchases.get(po[0].id()).lines().get(0).receivedQty()).isEqualByComparingTo("10");
    }

    @Test
    void retainSampleCannotBeOverdrawn() throws Exception {
        var sample = samples.findAll().get(0);
        int qty = sample.getRemainingQty();
        int ok = race("qa1", i -> retains.use(sample.getId(), new RetainService.UseReq(qty, "同时领用")));
        assertThat(ok).isEqualTo(1);
        assertThat(samples.findById(sample.getId()).orElseThrow().getRemainingQty()).isZero();
    }

    @Test
    void concurrentSalesNeverOversellAndAllAvailableStockCanSell() throws Exception {
        Long wh = mainWarehouse();
        Partner customer = partners.findAll().stream().filter(p -> "CUS-HD".equals(p.getCode())).findFirst().orElseThrow();
        Long fg = item("FG-SER-001").getId();
        // 这个客户能分配到的量（已放行、满足效期门槛），不含待放行的批次
        BigDecimal sellable = inventory.allocate(fg, new BigDecimal("99999999"), List.of(wh), customer.getMinShelfPct() / 100.0).picks().stream()
                .map(p -> p.qty()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal available = stocks.findAll().stream().filter(s -> s.getItemId().equals(fg) && s.getWarehouseId().equals(wh))
                .map(s -> s.getQty()).reduce(BigDecimal.ZERO, BigDecimal::add);
        // 每单量跨越多个批次：后到的必须按前一单扣完后的最新库存分配，不能因为读到旧数据误报库存不足
        BigDecimal each = sellable.divide(BigDecimal.valueOf(THREADS + 1), 0, java.math.RoundingMode.DOWN);
        int ok = race("sales1", i -> sales.create(new SalesService.SoReq(customer.getId(), wh, List.of(new SalesService.SoLineReq(fg, each)))));
        assertThat(ok).isEqualTo(THREADS);
        assertThat(stocks.findAll()).allMatch(s -> s.getQty().signum() >= 0);
        BigDecimal after = stocks.findAll().stream().filter(s -> s.getItemId().equals(fg) && s.getWarehouseId().equals(wh))
                .map(s -> s.getQty()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(after).isEqualByComparingTo(available.subtract(each.multiply(BigDecimal.valueOf(THREADS))));
    }
}
