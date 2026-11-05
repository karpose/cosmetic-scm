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
    @Autowired com.cosmetic.scm.inventory.OpeningStockService opening;
    @Autowired com.cosmetic.scm.security.UserRepository users;
    @Autowired com.cosmetic.scm.dealer.DealerService dealers;
    @Autowired com.cosmetic.scm.dealer.DealerOrderRepository dealerOrders;
    @Autowired com.cosmetic.scm.salesreturn.ReturnService returnService;
    @Autowired com.cosmetic.scm.salesreturn.SalesReturnLineRepository returnLines;
    @Autowired com.cosmetic.scm.recall.RecallService recalls;
    @Autowired com.cosmetic.scm.recall.RecallRepository recallRepo;
    @Autowired com.cosmetic.scm.serial.SerialService serials;
    @Autowired com.cosmetic.scm.serial.SerialCodeRepository serialCodes;
    @Autowired com.cosmetic.scm.integration.IntegrationService integration;
    @Autowired com.cosmetic.scm.integration.ChannelOrderRepository channelOrders;
    @Autowired com.cosmetic.scm.plm.FormulaService formulas;
    @Autowired com.cosmetic.scm.plm.FormulaVersionRepository formulaVersions;
    @Autowired com.cosmetic.scm.plm.FilingService filings;
    @Autowired com.cosmetic.scm.plm.ProductFilingRepository filingRepo;
    @Autowired com.cosmetic.scm.qms.DeviationService deviations;
    @Autowired com.cosmetic.scm.qms.DeviationRepository deviationRepo;
    @Autowired com.cosmetic.scm.qms.CapaRepository capaRepo;
    @Autowired com.cosmetic.scm.quality.QualityService quality;

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
                } catch (com.cosmetic.scm.common.BizException | org.springframework.dao.ConcurrencyFailureException
                         | org.springframework.dao.DataIntegrityViolationException e) {
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

    @Autowired com.cosmetic.scm.inventory.StockTxnRepository txns;

    /** 每个用例之后都对账：任何一个批次、仓库的库存余额都必须等于流水合计。第一个用例之后同时覆盖示例数据的所有流程。 */
    @org.junit.jupiter.api.AfterEach
    void stockAlwaysEqualsItsLedger() {
        java.util.Map<String, BigDecimal> ledger = new java.util.HashMap<>();
        txns.findAll().forEach(t -> ledger.merge(t.getBatchId() + "/" + t.getWarehouseId(), t.getQtyDelta(), BigDecimal::add));
        stocks.findAll().forEach(s -> {
            BigDecimal posted = ledger.remove(s.getBatchId() + "/" + s.getWarehouseId());
            assertThat(posted == null ? BigDecimal.ZERO : posted).as("批次 %d 仓库 %d 的流水合计", s.getBatchId(), s.getWarehouseId())
                    .isEqualByComparingTo(s.getQty());
        });
        // 有流水却没有库存行：合计必须是 0
        ledger.forEach((k, v) -> assertThat(v).as("没有库存行的 %s", k).isEqualByComparingTo(BigDecimal.ZERO));
    }

    // ---------- 下面的用例各用一个新建的成品，互相不影响 ----------

    private final java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();

    private com.cosmetic.scm.security.AppUser user(String name) {
        return users.findByUsername(name).orElseThrow();
    }

    private Partner customer() {
        return partners.findAll().stream().filter(p -> "CUS-HD".equals(p.getCode())).findFirst().orElseThrow();
    }

    private Long returnWarehouse() {
        return master.listWarehouses().stream().filter(w -> w.kind() == com.cosmetic.scm.domain.WarehouseKind.RETURN).findFirst().orElseThrow().id();
    }

    /** 新建一个成品（带备案编号），并期初导入一批指定状态的库存。 */
    private Item newProduct(String prefix, int qty, com.cosmetic.scm.domain.BatchStatus status) {
        String code = "FG-" + prefix + "-" + System.nanoTime() % 100000 + seq.incrementAndGet();
        as("admin", () -> master.createItem(new com.cosmetic.scm.master.MasterDtos.ItemReq(code, "并发" + prefix, com.cosmetic.scm.domain.ItemType.FG, "瓶", null, null,
                false, 36, "备案-" + code, new BigDecimal("30"), true, null, null)));
        addStock(code, code + "-B1", qty, status);
        return item(code);
    }

    private void addStock(String code, String batchNo, int qty, com.cosmetic.scm.domain.BatchStatus status) {
        as("qso1", () -> {
            var row = new com.cosmetic.scm.inventory.OpeningStockService.OpeningRow(code, batchNo, null, LocalDate.now().minusDays(30), null, "WH-MAIN",
                    BigDecimal.valueOf(qty), status, null, "RPT-" + batchNo, status == com.cosmetic.scm.domain.BatchStatus.RELEASED ? 2 : null, status == com.cosmetic.scm.domain.BatchStatus.RELEASED ? "留样柜" : null);
            var res = opening.run(new com.cosmetic.scm.inventory.OpeningStockService.OpeningReq(List.of(row), false, null), user("qso1"));
            assertThat(res.errors()).isEmpty();
        });
    }

    private Item newReleased(String prefix, int qty) {
        return newProduct(prefix, qty, com.cosmetic.scm.domain.BatchStatus.RELEASED);
    }

    private com.cosmetic.scm.inventory.Batch batchOf(Item fg) {
        return batches.findAll().stream().filter(b -> b.getItemId().equals(fg.getId())).findFirst().orElseThrow();
    }

    /** 这个物料在指定仓的库存合计。 */
    private BigDecimal qtyIn(Item it, Long wh) {
        return stocks.findAll().stream().filter(s -> s.getItemId().equals(it.getId()) && s.getWarehouseId().equals(wh))
                .map(s -> s.getQty()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void sell(Item fg, int qty) {
        as("sales1", () -> sales.create(new SalesService.SoReq(customer().getId(), mainWarehouse2(), List.of(new SalesService.SoLineReq(fg.getId(), BigDecimal.valueOf(qty))))));
    }

    private Long mainWarehouse2() {
        return master.listWarehouses().stream().filter(w -> "WH-MAIN".equals(w.code())).findFirst().orElseThrow().id();
    }

    @Test
    void openingImportRunsOnce() throws Exception {
        String code = "FG-OPEN-" + System.nanoTime() % 100000;
        as("admin", () -> master.createItem(new com.cosmetic.scm.master.MasterDtos.ItemReq(code, "并发期初", com.cosmetic.scm.domain.ItemType.FG, "瓶", null, null,
                false, 36, "备案-" + code, new BigDecimal("30"), true, null, null)));
        var row = new com.cosmetic.scm.inventory.OpeningStockService.OpeningRow(code, code + "-B1", null, LocalDate.now().minusDays(30), null, "WH-MAIN",
                new BigDecimal("50"), com.cosmetic.scm.domain.BatchStatus.RELEASED, null, "RPT", 2, "留样柜");
        race("qso1", i -> {
            var res = opening.run(new com.cosmetic.scm.inventory.OpeningStockService.OpeningReq(List.of(row), false, null), user("qso1"));
            if (!res.errors().isEmpty()) {
                throw new com.cosmetic.scm.common.BizException(res.errors().get(0).message());
            }
            return res;
        });
        assertThat(qtyIn(item(code), mainWarehouse2())).isEqualByComparingTo("50");
    }

    @Test
    void dealerOrderApprovedTwiceShipsOnce() throws Exception {
        Item fg = newReleased("DLR", 100);
        var order = new com.cosmetic.scm.dealer.DealerService.OrderView[1];
        as("dealer1", () -> order[0] = dealers.submit(new com.cosmetic.scm.dealer.DealerService.OrderReq(null, null,
                List.of(new com.cosmetic.scm.dealer.DealerService.LineReq(fg.getId(), new BigDecimal("5"))))));
        Long wh = mainWarehouse2();
        int ok = race("sales1", i -> dealers.approve(order[0].id(), new com.cosmetic.scm.dealer.DealerService.ApproveReq(wh)));
        assertThat(ok).isEqualTo(1);
        assertThat(qtyIn(fg, wh)).isEqualByComparingTo("95");
    }

    @Test
    void dealerOrderApproveAndRejectTogetherLeaveOneOutcome() throws Exception {
        Item fg = newReleased("DLX", 100);
        var order = new com.cosmetic.scm.dealer.DealerService.OrderView[1];
        as("dealer1", () -> order[0] = dealers.submit(new com.cosmetic.scm.dealer.DealerService.OrderReq(null, null,
                List.of(new com.cosmetic.scm.dealer.DealerService.LineReq(fg.getId(), new BigDecimal("5"))))));
        Long wh = mainWarehouse2();
        int ok = race("sales1", i -> i % 2 == 0 ? dealers.approve(order[0].id(), new com.cosmetic.scm.dealer.DealerService.ApproveReq(wh))
                : dealers.reject(order[0].id(), new com.cosmetic.scm.dealer.DealerService.RejectReq("同时驳回")));
        assertThat(ok).isEqualTo(1);
        var status = dealerOrders.findById(order[0].id()).orElseThrow().getStatus();
        assertThat(qtyIn(fg, wh)).isEqualByComparingTo(status == com.cosmetic.scm.domain.DealerOrderStatus.APPROVED ? "95" : "100");
    }

    @Test
    void returnsCannotExceedWhatWasShipped() throws Exception {
        Item fg = newReleased("RTN", 100);
        sell(fg, 10);
        Long batch = batchOf(fg).getId();
        Long rt = returnWarehouse();
        Long cus = customer().getId();
        int ok = race("sales1", i -> returnService.create(new com.cosmetic.scm.salesreturn.ReturnService.ReturnReq(cus, rt, null, "同时退货",
                List.of(new com.cosmetic.scm.salesreturn.ReturnService.ReturnLineReq(batch, BigDecimal.TEN)))));
        assertThat(ok).isEqualTo(1);
        assertThat(qtyIn(fg, rt)).isEqualByComparingTo("10");
    }

    @Test
    void returnLineDisposedTwiceMovesStockOnce() throws Exception {
        Item fg = newReleased("DSP", 100);
        sell(fg, 10);
        Long rt = returnWarehouse();
        Long wh = mainWarehouse2();
        var view = new com.cosmetic.scm.salesreturn.ReturnService.ReturnView[1];
        as("sales1", () -> view[0] = returnService.create(new com.cosmetic.scm.salesreturn.ReturnService.ReturnReq(customer().getId(), rt, null, "退回",
                List.of(new com.cosmetic.scm.salesreturn.ReturnService.ReturnLineReq(batchOf(fg).getId(), BigDecimal.TEN)))));
        Long line = view[0].lines().get(0).id();
        int ok = race("qa1", i -> returnService.dispose(line, new com.cosmetic.scm.salesreturn.ReturnService.DisposeReq(
                com.cosmetic.scm.domain.ReturnDisposition.RESTOCK, wh, "完好转可售", null), user("qa1")));
        assertThat(ok).isEqualTo(1);
        assertThat(qtyIn(fg, rt)).isZero();
        assertThat(qtyIn(fg, wh)).isEqualByComparingTo("100");
    }

    @Test
    void sameBatchCanOnlyHaveOneOpenRecall() throws Exception {
        Item fg = newReleased("RCL", 100);
        sell(fg, 10);
        String batchNo = batchOf(fg).getBatchNo();
        Long batchId = batchOf(fg).getId();
        int ok = race("qso1", i -> recalls.create(new com.cosmetic.scm.recall.RecallService.RecallReq(batchNo, null, "同时发起", null), user("qso1")));
        assertThat(ok).isEqualTo(1);
        assertThat(recallRepo.findByBatchIdIn(List.of(batchId))).hasSize(1);
    }

    @Test
    void recallNoticeAndCloseTakeEffectOnce() throws Exception {
        Item fg = newReleased("RCN", 100);
        sell(fg, 10);
        var view = new com.cosmetic.scm.recall.RecallService.RecallView[1];
        as("qso1", () -> view[0] = recalls.create(new com.cosmetic.scm.recall.RecallService.RecallReq(batchOf(fg).getBatchNo(), null, "通知", null), user("qso1")));
        Long notice = view[0].notices().get(0).id();
        assertThat(race("qa1", i -> recalls.notify(notice, new com.cosmetic.scm.recall.RecallService.NotifyReq("已电话通知")))).isEqualTo(1);
        assertThat(race("qso1", i -> recalls.close(view[0].id(), new com.cosmetic.scm.recall.RecallService.CloseReq("回收完成", null), user("qso1")))).isEqualTo(1);
    }

    @Test
    void serialCodesCannotExceedBatchQty() throws Exception {
        Item fg = newReleased("SER", 30);
        var b = batchOf(fg);
        int ok = race("qa1", i -> serials.generate(new com.cosmetic.scm.serial.SerialService.GenerateReq(b.getBatchNo(), 30)));
        assertThat(ok).isEqualTo(1);
        assertThat(serialCodes.countByBatchId(b.getId())).isEqualTo(30);
    }

    private com.cosmetic.scm.integration.IntegrationService.PushReq push(String orderNo, Item fg, int qty) {
        return new com.cosmetic.scm.integration.IntegrationService.PushReq("RACE", "CUS-HD", "WH-MAIN", List.of(
                new com.cosmetic.scm.integration.IntegrationService.PushOrder(orderNo, null,
                        List.of(new com.cosmetic.scm.integration.IntegrationService.PushLine(fg.getCode(), BigDecimal.valueOf(qty))))));
    }

    @Test
    void samePlatformOrderPushedTogetherShipsOnce() throws Exception {
        Item fg = newReleased("PSH", 100);
        race("system", i -> integration.receive(push("PUSH-" + fg.getCode(), fg, 5)));
        assertThat(qtyIn(fg, mainWarehouse2())).isEqualByComparingTo("95");
    }

    @Test
    void retryingFailedChannelOrderTwiceShipsOnce() throws Exception {
        Item fg = newReleased("RTY", 10);
        String no = "RETRY-" + fg.getCode();
        var res = integration.receive(push(no, fg, 20));
        assertThat(res.get(0).status()).isEqualTo(com.cosmetic.scm.domain.ChannelOrderStatus.FAILED);
        addStock(fg.getCode(), fg.getCode() + "-B2", 20, com.cosmetic.scm.domain.BatchStatus.RELEASED);
        Long id = channelOrders.findByPlatformAndPlatformOrderNo("RACE", no).orElseThrow().getId();
        race("sales1", i -> integration.retry(id));
        assertThat(channelOrders.findById(id).orElseThrow().getStatus()).isEqualTo(com.cosmetic.scm.domain.ChannelOrderStatus.SHIPPED);
        assertThat(qtyIn(fg, mainWarehouse2())).isEqualByComparingTo("10");
    }

    @Test
    void cancelledChannelOrderStaysCancelledEvenIfRetryIsFailingAtTheSameTime() throws Exception {
        // 重试失败后单独开事务写“失败”：不能盖掉同一时刻已成功的取消，否则之后还能重试出库
        for (int round = 0; round < 5; round++) {
            Item fg = newReleased("CNL", 1);
            String no = "CANCEL-" + fg.getCode();
            integration.receive(push(no, fg, 20));
            Long id = channelOrders.findByPlatformAndPlatformOrderNo("RACE", no).orElseThrow().getId();
            race("sales1", i -> i == 0 ? integration.cancelOrder(id, "同时取消") : integration.retry(id));
            assertThat(channelOrders.findById(id).orElseThrow().getStatus()).isEqualTo(com.cosmetic.scm.domain.ChannelOrderStatus.CANCELLED);
        }
    }

    @Test
    void formulaChangeOnlyHasOneDraftAndTakesEffectOnce() throws Exception {
        String code = "FG-FML-" + System.nanoTime() % 100000;
        Long rm = item("RM-GLY-002").getId();
        var fgId = new Long[1];
        as("admin", () -> {
            fgId[0] = master.createItem(new com.cosmetic.scm.master.MasterDtos.ItemReq(code, "并发配方", com.cosmetic.scm.domain.ItemType.FG, "瓶", null, null,
                    false, 36, null, new BigDecimal("30"), true, null, null)).getId();
            imports.importBoms(new com.cosmetic.scm.master.MasterImportService.BomImportReq(List.of(
                    new com.cosmetic.scm.master.MasterImportService.BomImportRow(code, "RM-GLY-002", new BigDecimal("0.001"), SupplyMode.BRAND)), false));
        });
        var draftReq = new com.cosmetic.scm.plm.FormulaService.DraftReq("调整用量", List.of(
                new com.cosmetic.scm.master.MasterDtos.BomLineReq(rm, new BigDecimal("0.002"), SupplyMode.BRAND)));
        long before = formulaVersions.findByFgItemIdOrderByVersionNoDesc(fgId[0]).size();
        assertThat(race("prod1", i -> formulas.createDraft(fgId[0], draftReq))).isEqualTo(1);
        var versions = formulaVersions.findByFgItemIdOrderByVersionNoDesc(fgId[0]);
        assertThat(versions).hasSize((int) before + 1);
        Long v = versions.get(0).getId();
        as("prod1", () -> formulas.submit(v));

        var filing = new com.cosmetic.scm.plm.FilingService.FilingView[1];
        as("qa1", () -> filing[0] = filings.create(new com.cosmetic.scm.plm.FilingService.FilingReq(fgId[0], com.cosmetic.scm.domain.FilingType.GENERAL, v, null, null)));
        assertThat(race("qa1", i -> filings.complete(filing[0].id(), new com.cosmetic.scm.plm.FilingService.CompleteReq("备案-" + i, LocalDate.now(), null)))).isEqualTo(1);

        assertThat(race("qso1", i -> formulas.approve(v, user("qso1")))).isEqualTo(1);
        assertThat(master.bomLines(fgId[0])).hasSize(1);
        assertThat(formulaVersions.findByFgItemIdOrderByVersionNoDesc(fgId[0]).stream()
                .filter(x -> x.getStatus() == com.cosmetic.scm.domain.FormulaStatus.EFFECTIVE)).hasSize(1);
    }

    /** 这个竞态窗口很小，单跑一轮几乎测不出来（CI 上才偶发），所以连续多轮。 */
    @Test
    void formulaDraftRaceRepeatedOnFreshProducts() throws Exception {
        Long rm = item("RM-GLY-002").getId();
        for (int round = 0; round < 40; round++) {
            String code = "FG-FRC-" + System.nanoTime() % 1000000 + "-" + round;
            var fgId = new Long[1];
            as("admin", () -> {
                fgId[0] = master.createItem(new com.cosmetic.scm.master.MasterDtos.ItemReq(code, "竞态配方", com.cosmetic.scm.domain.ItemType.FG, "瓶", null, null,
                        false, 36, null, new BigDecimal("30"), true, null, null)).getId();
                imports.importBoms(new com.cosmetic.scm.master.MasterImportService.BomImportReq(List.of(
                        new com.cosmetic.scm.master.MasterImportService.BomImportRow(code, "RM-GLY-002", new BigDecimal("0.001"), SupplyMode.BRAND)), false));
            });
            var req = new com.cosmetic.scm.plm.FormulaService.DraftReq("调整用量", List.of(
                    new com.cosmetic.scm.master.MasterDtos.BomLineReq(rm, new BigDecimal("0.002"), SupplyMode.BRAND)));
            assertThat(race("prod1", i -> formulas.createDraft(fgId[0], req))).as("第 %d 轮", round).isEqualTo(1);
        }
    }

    @Test
    void capaAndDeviationStepsTakeEffectOnce() throws Exception {
        var dev = new com.cosmetic.scm.qms.DeviationService.DeviationView[1];
        var capa = new com.cosmetic.scm.qms.DeviationService.CapaView[1];
        as("qa1", () -> {
            dev[0] = deviations.create(new com.cosmetic.scm.qms.DeviationService.DeviationReq(LocalDate.now(), com.cosmetic.scm.domain.DeviationArea.PRODUCTION,
                    com.cosmetic.scm.domain.DeviationLevel.MAJOR, null, "并发偏差", "描述", null, LocalDate.now().plusDays(7)));
            deviations.investigate(dev[0].id(), new com.cosmetic.scm.qms.DeviationService.InvestigateReq("根本原因"));
            capa[0] = deviations.createCapa(new com.cosmetic.scm.qms.DeviationService.CapaReq(com.cosmetic.scm.domain.CapaSource.DEVIATION, null, dev[0].id(),
                    com.cosmetic.scm.domain.CapaType.CORRECTIVE, "措施", "负责人", LocalDate.now().plusDays(7)));
        });
        assertThat(race("prod1", i -> deviations.finishCapa(capa[0].id(), new com.cosmetic.scm.qms.DeviationService.CapaDoneReq("已完成")))).isEqualTo(1);
        assertThat(race("qa1", i -> deviations.verifyCapa(capa[0].id(), new com.cosmetic.scm.qms.DeviationService.CapaVerifyReq(true, "有效", null), user("qa1")))).isEqualTo(1);
        assertThat(race("qa1", i -> deviations.close(dev[0].id(), new com.cosmetic.scm.qms.DeviationService.CloseReq("关闭", null), user("qa1")))).isEqualTo(1);
    }

    @Test
    void marketReleaseTwiceTakesEffectOnce() throws Exception {
        Item fg = newProduct("REL", 50, com.cosmetic.scm.domain.BatchStatus.PENDING_RELEASE);
        Long batch = batchOf(fg).getId();
        long before = samples.count();
        int ok = race("qso1", i -> quality.marketRelease(batch, new com.cosmetic.scm.quality.QualityService.ReleaseReq(
                com.cosmetic.scm.domain.Decision.RELEASED, "RPT-" + i, "同时放行", true, true, true, true, 2, "留样柜", null), user("qso1")));
        assertThat(ok).isEqualTo(1);
        assertThat(samples.count()).isEqualTo(before + 1);
    }
}
