package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.dealer.DealerService;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.Decision;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.ReturnDisposition;
import com.cosmetic.scm.domain.SupplyMode;
import com.cosmetic.scm.domain.WarehouseKind;
import com.cosmetic.scm.integration.IntegrationService;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.inventory.OpeningStockService;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.ItemRepository;
import com.cosmetic.scm.master.MasterDtos;
import com.cosmetic.scm.master.MasterImportService;
import com.cosmetic.scm.master.MasterService;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.PartnerRepository;
import com.cosmetic.scm.production.ProductionService;
import com.cosmetic.scm.quality.QualityService;
import com.cosmetic.scm.recall.RecallService;
import com.cosmetic.scm.sales.SalesService;
import com.cosmetic.scm.salesreturn.ReturnService;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.UserRepository;
import com.cosmetic.scm.trace.TraceService;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * 召回演练：搭一个答案已知的场景，核对系统查出来的流向。
 *
 * 原料 A1（供应商批号 SUP-A1，0.6 kg，先到期）、A2（SUP-A2，5 kg）；工厂代采原料批号 OEM-LOT-9。
 * MO1 → F1（用 A1 0.6 + A2 0.4）；MO2 → F2（只用 A2）；MO3 已发料未完工（只用 A2）。
 * F1 发货：经销商审核 40 给 C1，销售 30 给 C2，电商 5 给 C3；C2 退 10 转可售后再发 10 给 C3。
 * 所以 F1 在外 = 40 + 20 + 15 = 75，账上发出合计 85，库存 25。F2 没有发货。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-drill;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000")
class TraceRecallDrillTest {

    @Autowired MasterService master;
    @Autowired MasterImportService imports;
    @Autowired OpeningStockService opening;
    @Autowired ProductionService production;
    @Autowired QualityService quality;
    @Autowired SalesService sales;
    @Autowired DealerService dealers;
    @Autowired IntegrationService integration;
    @Autowired ReturnService returns;
    @Autowired RecallService recalls;
    @Autowired TraceService trace;
    @Autowired BatchRepository batches;
    @Autowired ItemRepository items;
    @Autowired PartnerRepository partners;
    @Autowired UserRepository users;

    private Long wh;
    private Long rtWh;
    private Partner c1;
    private Partner c2;
    private Partner c3;
    private Item fg;
    private String f1;
    private String f2;
    private ProductionService.MoView mo3;

    private <T> T as(String user, Supplier<T> s) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        try {
            return s.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private AppUser user(String name) {
        return users.findByUsername(name).orElseThrow();
    }

    private Partner customer(String code) {
        return as("admin", () -> {
            master.createPartner(new MasterDtos.PartnerReq(code, "演练客户" + code, PartnerKind.CUSTOMER, null, null, null, null, "线下", 0, null, null, true, false));
            return partners.findByCode(code).orElseThrow();
        });
    }

    private void openingRow(String code, String no, String ext, LocalDate exp, String qty) {
        as("qso1", () -> {
            var row = new OpeningStockService.OpeningRow(code, no, ext, LocalDate.now().minusDays(30), exp, "WH-MAIN", new BigDecimal(qty), BatchStatus.RELEASED, null, "COA-" + no, null, null);
            assertThat(opening.run(new OpeningStockService.OpeningReq(List.of(row), false, null), user("qso1")).errors()).isEmpty();
            return null;
        });
    }

    private Partner oem() {
        return partners.findAll().stream().filter(p -> p.getKind() == PartnerKind.OEM).findFirst().orElseThrow();
    }

    private ProductionService.MoView issued() {
        var mo = as("prod1", () -> production.create(new ProductionService.CreateMoReq(oem().getId(), fg.getId(), new BigDecimal("100"), null)));
        as("prod1", () -> production.issue(mo.id()));
        return mo;
    }

    private String complete(ProductionService.MoView mo) {
        var ext = as("prod1", () -> production.detail(mo.id())).requirements().stream().filter(r -> r.supplyMode() == SupplyMode.OEM)
                .map(r -> new ProductionService.ExtMaterialReq(r.itemId(), "OEM-LOT-9", r.requiredQty())).toList();
        as("prod1", () -> production.complete(mo.id(), new ProductionService.CompleteReq(new BigDecimal("100"), LocalDate.now(), null, wh, true, null, ext)));
        return as("prod1", () -> production.detail(mo.id())).mo().outBatchNo();
    }

    private QualityService.ReleaseReq release() {
        return new QualityService.ReleaseReq(Decision.RELEASED, "RPT-DRILL", "放行", true, true, true, true, 2, "留样柜", null);
    }

    private Long batchId(String no) {
        return batches.findByBatchNo(no).orElseThrow().getId();
    }

    private void sell(Partner c, int qty) {
        as("sales1", () -> sales.create(new SalesService.SoReq(c.getId(), wh, List.of(new SalesService.SoLineReq(fg.getId(), BigDecimal.valueOf(qty))))));
    }

    @BeforeAll
    void buildScenario() {
        wh = master.listWarehouses().stream().filter(w -> "WH-MAIN".equals(w.code())).findFirst().orElseThrow().id();
        rtWh = master.listWarehouses().stream().filter(w -> w.kind() == WarehouseKind.RETURN).findFirst().orElseThrow().id();
        c1 = partners.findByCode("CUS-HD").orElseThrow();
        c2 = customer("DRILL-C2");
        c3 = customer("DRILL-C3");
        as("admin", () -> {
            for (String[] rm : new String[][] {{"RM-DRILL-A", "演练原料 A"}, {"RM-DRILL-B", "演练代采原料 B"}}) {
                master.createItem(new MasterDtos.ItemReq(rm[0], rm[1], ItemType.RM, "kg", null, null, false, 36, null, null, true, null, null));
            }
            master.createItem(new MasterDtos.ItemReq("FG-DRILL", "演练精华", ItemType.FG, "瓶", null, null, false, 36, "备案-DRILL", new BigDecimal("30"), true, null, null));
            imports.importBoms(new MasterImportService.BomImportReq(List.of(
                    new MasterImportService.BomImportRow("FG-DRILL", "RM-DRILL-A", new BigDecimal("0.01"), SupplyMode.BRAND),
                    new MasterImportService.BomImportRow("FG-DRILL", "RM-DRILL-B", new BigDecimal("0.02"), SupplyMode.OEM)), false));
            return null;
        });
        fg = items.findAll().stream().filter(i -> "FG-DRILL".equals(i.getCode())).findFirst().orElseThrow();
        openingRow("RM-DRILL-A", "A1", "SUP-A1", LocalDate.now().plusMonths(6), "0.6");
        openingRow("RM-DRILL-A", "A2", "SUP-A2", LocalDate.now().plusMonths(24), "5");

        f1 = complete(issued());
        as("qso1", () -> quality.marketRelease(batchId(f1), release(), user("qso1")));
        Long f1Id = batchId(f1);

        // 经销商 40、销售 30、电商 5
        var order = as("dealer1", () -> dealers.submit(new DealerService.OrderReq(null, null, List.of(new DealerService.LineReq(fg.getId(), new BigDecimal("40"))))));
        as("sales1", () -> dealers.approve(order.id(), new DealerService.ApproveReq(wh)));
        sell(c2, 30);
        assertThat(integration.receive(new IntegrationService.PushReq("DRILL", c3.getCode(), "WH-MAIN", List.of(new IntegrationService.PushOrder("DRILL-1", null,
                List.of(new IntegrationService.PushLine("FG-DRILL", new BigDecimal("5"))))))).get(0).error()).isNull();

        // C2 退 10，转可售后再发给 C3
        var rt = as("sales1", () -> returns.create(new ReturnService.ReturnReq(c2.getId(), rtWh, null, "外箱破损", List.of(new ReturnService.ReturnLineReq(f1Id, BigDecimal.TEN)))));
        as("qa1", () -> returns.dispose(rt.lines().get(0).id(), new ReturnService.DisposeReq(ReturnDisposition.RESTOCK, wh, "完好", null), user("qa1")));
        sell(c3, 10);

        f2 = complete(issued());
        as("qso1", () -> quality.marketRelease(batchId(f2), release(), user("qso1")));
        mo3 = issued();
    }

    private Map<String, BigDecimal> shippedTo(TraceService.TraceView t) {
        return t.shipments().stream().collect(Collectors.toMap(TraceService.ShipmentView::customerName, TraceService.ShipmentView::qty, BigDecimal::add));
    }

    @Test
    void materialTraceFindsExactlyTheProductsAndCustomersItReached() {
        var a1 = trace.trace("A1");
        assertThat(a1.finishedBatches()).extracting(b -> b.batchNo()).containsExactly(f1);
        assertThat(shippedTo(a1)).containsOnlyKeys(c1.getName(), c2.getName(), c3.getName());
        assertThat(shippedTo(a1).get(c1.getName())).isEqualByComparingTo("40");
        assertThat(shippedTo(a1).get(c2.getName())).isEqualByComparingTo("30");
        assertThat(shippedTo(a1).get(c3.getName())).isEqualByComparingTo("15");

        var a2 = trace.trace("A2");
        assertThat(a2.finishedBatches()).extracting(b -> b.batchNo()).containsExactlyInAnyOrder(f1, f2);
        // 还没完工的单也要列出来：它的产出之后也受影响
        assertThat(a2.downstreamOrders()).extracting(TraceService.DownstreamMoView::moNo).contains(mo3.moNo());
    }

    @Test
    void productTraceShowsEveryMaterialLotAndItsQuantity() {
        var t = trace.trace(f1);
        Map<String, BigDecimal> used = t.upstream().stream().filter(u -> u.batchNo() != null)
                .collect(Collectors.toMap(TraceService.UpstreamView::batchNo, TraceService.UpstreamView::qty));
        assertThat(used.get("A1")).isEqualByComparingTo("0.6");
        assertThat(used.get("A2")).isEqualByComparingTo("0.4");
        assertThat(t.upstream()).filteredOn(u -> u.batchNo() == null).extracting(TraceService.UpstreamView::extBatchNo).containsExactly("OEM-LOT-9");
    }

    @Test
    void lotCanBeFoundBySupplierOrFactoryBatchNumber() {
        assertThat(trace.search("SUP-A1")).extracting(TraceService.LotHit::batchNo).containsExactly("A1");
        assertThat(trace.search(" SUP-A2 ").get(0).matchLabel()).isEqualTo("供应商批号");
        // 工厂代采没有系统批次：直接给出用了它的成品
        assertThat(trace.search("OEM-LOT-9")).extracting(TraceService.LotHit::batchNo).contains(f1, f2);
        assertThat(trace.search("不存在的批号")).isEmpty();
    }

    @Test
    void openingBatchCanBeRecalledEvenWithoutShipmentsInTheSystem() {
        as("admin", () -> master.createItem(new MasterDtos.ItemReq("FG-OPEN-RC", "老产品", ItemType.FG, "瓶", null, null, false, 36, "备案-OLD", new BigDecimal("30"), true, null, null)));
        as("qso1", () -> {
            var row = new OpeningStockService.OpeningRow("FG-OPEN-RC", "OLD-1", null, LocalDate.now().minusDays(30), null, "WH-MAIN", BigDecimal.TEN, BatchStatus.RELEASED, null, "RPT", 2, "留样柜");
            assertThat(opening.run(new OpeningStockService.OpeningReq(List.of(row), false, null), user("qso1")).errors()).isEmpty();
            return null;
        });
        var v = as("qso1", () -> recalls.create(new RecallService.RecallReq("OLD-1", null, "上线前已发货", null), user("qso1")));
        assertThat(v.openingBatch()).isTrue();
        assertThat(v.notices()).isEmpty();
        assertThat(batches.findByBatchNo("OLD-1").orElseThrow().getStatus()).isEqualTo(BatchStatus.FROZEN);
        // 系统内生产、确实没发货的批次仍然不需要召回
        assertThatThrownBy(() -> as("qso1", () -> recalls.create(new RecallService.RecallReq(f2, null, "没发货", null), user("qso1"))))
                .isInstanceOf(BizException.class).hasMessageContaining("无需召回");
    }

    @Test
    void productsMadeFromFrozenMaterialCannotBeReleased() {
        as("qso1", () -> quality.freeze(new QualityService.FreezeReq(List.of("A2"), "供应商通报污染", null), user("qso1")));
        try {
            String f3 = complete(mo3);
            assertThatThrownBy(() -> as("qso1", () -> quality.marketRelease(batchId(f3), release(), user("qso1"))))
                    .isInstanceOf(BizException.class).hasMessageContaining("A2");
        } finally {
            as("qso1", () -> quality.unfreeze(batchId("A2"), new QualityService.UnfreezeReq("调查后排除", null), user("qso1")));
        }
    }

    @Test
    void recallReachesEveryCustomerAndBlocksFurtherShipments() {
        var view = as("qso1", () -> recalls.create(new RecallService.RecallReq(f1, null, "演练", null), user("qso1")));
        assertThat(view.notices()).extracting(RecallService.NoticeView::customerName).containsExactlyInAnyOrder(c1.getName(), c2.getName(), c3.getName());
        assertThat(view.shippedQty()).isEqualByComparingTo("85");
        // 还在外面的是 75：C2 那 10 件是普通退货，已转可售再发给 C3
        assertThat(view.returnedQty()).isEqualByComparingTo("10");
        assertThat(view.notices()).filteredOn(n -> n.customerName().equals(c2.getName())).extracting(RecallService.NoticeView::returnedQty)
                .allSatisfy(q -> assertThat(q).isEqualByComparingTo("10"));
        assertThat(view.openingBatch()).isFalse();
        assertThat(batches.findByBatchNo(f1).orElseThrow().getStatus()).isEqualTo(BatchStatus.FROZEN);

        // 这时只有 F2 能卖，超过 F2 的量就不够了，绝不能出 F1
        assertThatThrownBy(() -> sell(c1, 101)).isInstanceOf(BizException.class);
        // 召回没结束不能解冻，否则已召回的批次又能发货
        assertThatThrownBy(() -> as("qso1", () -> quality.unfreeze(batchId(f1), new QualityService.UnfreezeReq("误操作", null), user("qso1"))))
                .isInstanceOf(BizException.class).hasMessageContaining("召回");
    }
}
