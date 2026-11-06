package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.dashboard.DashboardService;
import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.CaseStatus;
import com.cosmetic.scm.domain.ReturnDisposition;
import com.cosmetic.scm.domain.rules.FefoAllocator;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.inventory.InventoryService;
import com.cosmetic.scm.inventory.Stock;
import com.cosmetic.scm.inventory.StockOpsService;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.master.ItemRepository;
import com.cosmetic.scm.master.MasterDtos.ItemReq;
import com.cosmetic.scm.master.MasterImportService;
import com.cosmetic.scm.master.PartnerRepository;
import com.cosmetic.scm.master.WarehouseRepository;
import com.cosmetic.scm.recall.RecallService;
import com.cosmetic.scm.sales.SalesService;
import com.cosmetic.scm.salesreturn.ReturnService;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.UserRepository;
import com.cosmetic.scm.trace.TraceService;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 启动时示例数据会通过真实服务走完一遍主线；这里校验结果和权限边界。
 * 会改数据的用例加 @Transactional，结束后回滚，不影响其它用例看到的示例数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MainLineIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    BatchRepository batches;
    @Autowired
    InventoryService inventory;
    @Autowired
    TraceService trace;
    @Autowired
    DashboardService dashboard;
    @Autowired
    SalesService sales;
    @Autowired
    PartnerRepository partners;
    @Autowired
    WarehouseRepository warehouses;
    @Autowired
    UserRepository users;
    @Autowired
    ReturnService returns;
    @Autowired
    RecallService recalls;
    @Autowired
    StockOpsService stockOps;
    @Autowired
    MasterImportService importer;
    @Autowired
    ItemRepository items;

    @Test
    void demoMainLineIsConsistent() {
        DashboardService.Summary s = dashboard.summary();
        assertThat(s.counts().quarantine()).isEqualTo(1);
        assertThat(s.counts().pendingRelease()).isEqualTo(1);
        assertThat(s.counts().wipOrders()).isEqualTo(2);  // 待发料的自产面霜 + 等受托厂申报的委托单

        assertThat(s.counts().openComplaints()).isEqualTo(1);
        assertThat(s.counts().pendingReturns()).isEqualTo(1);

        Batch released = firstSerumBatch();
        // 9950 入库 - 3000 出库 + 天猫退回隔离区的 20
        assertThat(inventory.onHand(released.getId())).isEqualByComparingTo("6970");

        TraceService.TraceView t = trace.trace(released.getBatchNo());
        assertThat(t.shipments()).hasSize(2);
        assertThat(t.upstream()).anyMatch(u -> "工厂代采".equals(u.supplyLabel()) && u.extBatchNo() != null);
        assertThat(t.upstream()).anyMatch(u -> "品牌供料".equals(u.supplyLabel()) && u.batchNo() != null);
        assertThat(t.decisions()).isNotEmpty();
    }

    @Test
    void customerShelfLifeThresholdSkipsOldBatch() {
        Batch old = batches.findAll().stream()
                .filter(b -> b.getBatchNo().startsWith("A3-") && b.getStatus() == BatchStatus.RELEASED)
                .min(Comparator.comparing(Batch::getMfgDate)).orElseThrow();
        Long main = warehouseId("WH-MAIN");
        List<SalesService.SoLineReq> lines = List.of(new SalesService.SoLineReq(old.getItemId(), new BigDecimal("100")));

        SalesService.AllocLineView jd = sales.preview(new SalesService.SoReq(
                partners.findByCode("CUS-JD").orElseThrow().getId(), main, lines)).lines().get(0);
        assertThat(jd.excludedQty()).isEqualByComparingTo("1000");
        assertThat(jd.picks()).noneMatch(p -> p.batchNo().equals(old.getBatchNo()));

        SalesService.AllocLineView tm = sales.preview(new SalesService.SoReq(
                partners.findByCode("CUS-TM").orElseThrow().getId(), main, lines)).lines().get(0);
        assertThat(tm.excludedQty()).isZero();
        assertThat(tm.picks().get(0).batchNo()).isEqualTo(old.getBatchNo());
    }

    @Test
    void returnAreaStockIsNeverAllocated() {
        Batch serum = firstSerumBatch();
        Long rtn = warehouseId("WH-RTN");
        assertThat(inventory.stockOfBatch(serum.getId()))
                .anyMatch(st -> st.getWarehouseId().equals(rtn) && st.getQty().compareTo(new BigDecimal("20")) == 0);

        FefoAllocator.Result all = inventory.allocate(serum.getItemId(), new BigDecimal("100000"), null, 0);
        assertThat(all.picks()).noneMatch(p -> p.warehouseId().equals(rtn));

        List<SalesService.SoLineReq> lines = List.of(new SalesService.SoLineReq(serum.getItemId(), BigDecimal.ONE));
        assertThatThrownBy(() -> sales.preview(new SalesService.SoReq(customerId("CUS-SY"), rtn, lines)))
                .isInstanceOf(BizException.class).hasMessageContaining("退货");
    }

    @Test
    @Transactional
    @WithMockUser(username = "qa1")
    void returnIsCappedByShippedQtyAndRestockMovesStock() {
        Batch serum = firstSerumBatch();
        AppUser qa = users.findByUsername("qa1").orElseThrow();
        Long main = warehouseId("WH-MAIN");
        Long rtn = warehouseId("WH-RTN");

        // 天猫收到 1000，已退 20，最多还能退 980
        assertThatThrownBy(() -> returns.create(new ReturnService.ReturnReq(customerId("CUS-TM"), rtn, null, "超量",
                List.of(new ReturnService.ReturnLineReq(serum.getId(), new BigDecimal("981"))))))
                .isInstanceOf(BizException.class).hasMessageContaining("980");

        ReturnService.ReturnView pending = returns.list().stream().filter(r -> r.pendingLines() > 0).findFirst().orElseThrow();
        BigDecimal mainBefore = qtyAt(serum.getId(), main);
        ReturnService.ReturnView done = returns.dispose(pending.lines().get(0).id(),
                new ReturnService.DisposeReq(ReturnDisposition.RESTOCK, main, "外观完好，复检合格", "x"), qa);
        assertThat(done.pendingLines()).isZero();
        assertThat(qtyAt(serum.getId(), main)).isEqualByComparingTo(mainBefore.add(new BigDecimal("20")));
        assertThat(qtyAt(serum.getId(), rtn)).isZero();
    }

    @Test
    @Transactional
    @WithMockUser(username = "qso1")
    void recallFreezesBatchTracksRecoveryAndClosesOnlyWhenAllNotified() {
        Batch serum = firstSerumBatch();
        AppUser qso = users.findByUsername("qso1").orElseThrow();

        RecallService.RecallView r = recalls.create(new RecallService.RecallReq(serum.getBatchNo(), null, "测试召回", "x"), qso);
        assertThat(batches.findById(serum.getId()).orElseThrow().getStatus()).isEqualTo(BatchStatus.FROZEN);
        assertThat(r.notices()).hasSize(2);
        assertThat(r.shippedQty()).isEqualByComparingTo("3000");

        returns.create(new ReturnService.ReturnReq(customerId("CUS-JD"), warehouseId("WH-RTN"), r.id(), "召回回收",
                List.of(new ReturnService.ReturnLineReq(serum.getId(), new BigDecimal("500")))));
        RecallService.CloseReq close = new RecallService.CloseReq("已回收并销毁", "x");
        assertThatThrownBy(() -> recalls.close(r.id(), close, qso)).hasMessageContaining("未通知");

        r.notices().forEach(n -> recalls.notify(n.id(), new RecallService.NotifyReq("电话通知")));
        RecallService.RecallView closed = recalls.close(r.id(), close, qso);
        assertThat(closed.status()).isEqualTo(CaseStatus.CLOSED);
        assertThat(closed.recoveredQty()).isEqualByComparingTo("500");
        assertThat(trace.trace(serum.getBatchNo()).recalls()).hasSize(1);
    }

    @Test
    @Transactional
    @WithMockUser(username = "purchase1")
    void stockCountAdjustsToCountedQtyAndTransferMovesStock() {
        Long main = warehouseId("WH-MAIN");
        Long oem = warehouseId("WH-A3");
        Stock row = inventory.listStock(null, main, null, false).stream()
                .filter(r -> !"FG".equals(r.itemType())).findFirst()
                .map(r -> inventory.stockOfBatch(r.batchId()).stream()
                        .filter(st -> st.getWarehouseId().equals(main)).findFirst().orElseThrow())
                .orElseThrow();
        BigDecimal book = row.getQty();
        BigDecimal counted = book.subtract(BigDecimal.ONE);
        List<StockOpsService.CountLineReq> count = List.of(new StockOpsService.CountLineReq(row.getBatchId(), counted));

        StockOpsService.DocView doc = stockOps.adjust(new StockOpsService.AdjustReq(main, "月度盘点", count));
        assertThat(doc.lines().get(0).qty()).isEqualByComparingTo("-1");
        assertThat(qtyAt(row.getBatchId(), main)).isEqualByComparingTo(counted);
        assertThatThrownBy(() -> stockOps.adjust(new StockOpsService.AdjustReq(main, "复盘", count)))
                .hasMessageContaining("无需调整");
        // 该仓没有这个批次的记录：不能按账面 0 算盘盈，否则错仓提交会凭空多出库存
        assertThatThrownBy(() -> stockOps.adjust(new StockOpsService.AdjustReq(oem, "错仓",
                List.of(new StockOpsService.CountLineReq(row.getBatchId(), new BigDecimal("777"))))))
                .hasMessageContaining("没有库存记录");

        stockOps.transfer(new StockOpsService.TransferReq(main, oem, "调往代工厂",
                List.of(new StockOpsService.QtyLineReq(row.getBatchId(), BigDecimal.ONE))));
        assertThat(qtyAt(row.getBatchId(), main)).isEqualByComparingTo(counted.subtract(BigDecimal.ONE));
        assertThat(qtyAt(row.getBatchId(), oem)).isEqualByComparingTo("1");
    }

    /** 不加 @Transactional：导入必须在自己的事务里回滚，才能验证“全成或全不成”。成功导入的一行会留在库里。 */
    @Test
    @WithMockUser(username = "admin")
    void importIsAllOrNothingAndReportsEveryBadRow() {
        long before = items.count();
        ItemReq ok = new ItemReq("RM-IMP-001", "导入测试原料", ItemType.RM, "kg", null, null, false, 24, null, null, true, null, null);
        ItemReq noShelf = new ItemReq("FG-IMP-002", "导入测试成品", ItemType.FG, "瓶", null, null, false, null, null, null, true, null, null);
        ItemReq badCode = new ItemReq("坏 编码", "名称", ItemType.RM, "kg", null, null, false, null, null, null, true, null, null);

        MasterImportService.ImportResult bad = importer.importItems(
                new MasterImportService.ItemImportReq(List.of(ok, noShelf, badCode, ok), false));
        assertThat(bad.applied()).isFalse();
        assertThat(bad.errors()).extracting(MasterImportService.RowError::line).containsExactly(3, 4, 5);
        assertThat(items.count()).isEqualTo(before);

        MasterImportService.ImportResult dry = importer.importItems(new MasterImportService.ItemImportReq(List.of(ok), true));
        assertThat(dry.errors()).isEmpty();
        assertThat(dry.created()).isEqualTo(1);
        assertThat(items.count()).isEqualTo(before);

        MasterImportService.ImportResult done = importer.importItems(new MasterImportService.ItemImportReq(List.of(ok), false));
        assertThat(done.applied()).isTrue();
        assertThat(items.count()).isEqualTo(before + 1);
    }

    @Test
    void apiRequiresLogin() throws Exception {
        mvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyQualitySafetyOfficerCanMarketRelease() throws Exception {
        Batch pending = batches.findByStatusInOrderByIdDesc(List.of(BatchStatus.PENDING_RELEASE)).get(0);
        String body = json.writeValueAsString(Map.of(
                "decision", "RELEASED", "reportNo", "QC-T", "note", "test", "oemReleaseReviewed", true,
                "inspectionPassed", true, "recordsComplete", true, "consistentWithFiling", true,
                "retainQty", 6, "retainLocation", "A-1", "password", DemoDataLoader.DEMO_PASSWORD));
        String url = "/api/quality/batches/" + pending.getId() + "/market-release";

        mvc.perform(post(url).header("Authorization", "Bearer " + login("qa1"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        String wrongPassword = body.replace(DemoDataLoader.DEMO_PASSWORD, "wrong-password");
        mvc.perform(post(url).header("Authorization", "Bearer " + login("qso1"))
                        .contentType(MediaType.APPLICATION_JSON).content(wrongPassword))
                .andExpect(status().isBadRequest());
    }

    /** 第一批委托生产的精华液（A3-…-01）：已放行，发给京东 2000、天猫 1000，天猫退回 20。 */
    private Batch firstSerumBatch() {
        return batches.findAll().stream()
                .filter(b -> b.getBatchNo().startsWith("A3-") && b.getStatus() == BatchStatus.RELEASED)
                .min(Comparator.comparing(Batch::getId)).orElseThrow();
    }

    private Long warehouseId(String code) {
        return warehouses.findAll().stream().filter(w -> code.equals(w.getCode())).findFirst().orElseThrow().getId();
    }

    private Long customerId(String code) {
        return partners.findByCode(code).orElseThrow().getId();
    }

    private BigDecimal qtyAt(Long batchId, Long warehouseId) {
        return inventory.stockOfBatch(batchId).stream().filter(st -> st.getWarehouseId().equals(warehouseId))
                .map(Stock::getQty).findFirst().orElse(BigDecimal.ZERO);
    }

    private String login(String username) throws Exception {
        String resp = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", DemoDataLoader.DEMO_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("token").asText();
    }
}
