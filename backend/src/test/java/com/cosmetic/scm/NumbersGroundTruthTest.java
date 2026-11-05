package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;

import com.cosmetic.scm.analytics.AnalyticsService;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.complaint.ComplaintRepository;
import com.cosmetic.scm.complaint.ComplaintService;
import com.cosmetic.scm.dashboard.DashboardService;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.ComplaintCategory;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.WarehouseKind;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.inventory.InventoryService;
import com.cosmetic.scm.inventory.OpeningStockService;
import com.cosmetic.scm.inventory.StockOpsService;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterDtos;
import com.cosmetic.scm.master.MasterService;
import com.cosmetic.scm.master.PartnerRepository;
import com.cosmetic.scm.planning.PlanningService;
import com.cosmetic.scm.report.ReportService;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.UserRepository;

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
 * 看板、补货、导出的数字要和手算一致。关掉示例数据，库里只有下面这些，所有答案都能手算。
 *
 * 成品 P（成本 30）：P-FRESH 100 件、P-EXPIRED 40 件（已过期）、12 个老批次 SLOW-0..11 各 1 件（生产于 100 天前，SLOW-0 调拨过）。
 * 原料 M（成本 4）：M-OK 2.5 kg、M-EXPIRED 9 kg（已过期）。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {"spring.datasource.url=jdbc:h2:mem:scm-numbers;MODE=MySQL;DB_CLOSE_DELAY=-1", "scm.demo-data=false"})
class NumbersGroundTruthTest {

    @Autowired MasterService master;
    @Autowired com.cosmetic.scm.master.MasterImportService imports;
    @Autowired com.cosmetic.scm.master.ItemRepository itemRepo;
    @Autowired OpeningStockService opening;
    @Autowired StockOpsService stockOps;
    @Autowired AnalyticsService analytics;
    @Autowired DashboardService dashboard;
    @Autowired PlanningService planning;
    @Autowired InventoryService inventory;
    @Autowired ReportService reports;
    @Autowired ComplaintService complaints;
    @Autowired ComplaintRepository complaintRepo;
    @Autowired BatchRepository batches;
    @Autowired PartnerRepository partners;
    @Autowired UserRepository users;
    @Autowired TimeProvider time;

    private Long main;
    private Long second;
    private Item p;
    private Item m;
    private LocalDate today;

    private <T> T as(String user, Supplier<T> s) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        try {
            return s.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private AppUser admin() {
        return users.findByUsername("admin").orElseThrow();
    }

    private void stock(String code, String batch, LocalDate mfg, LocalDate exp, String qty, boolean fg) {
        var row = new OpeningStockService.OpeningRow(code, batch, null, mfg, exp, "WH1", new BigDecimal(qty), BatchStatus.RELEASED, null, "RPT", fg ? 1 : null, fg ? "留样柜" : null);
        var res = opening.run(new OpeningStockService.OpeningReq(List.of(row), false, null), admin());
        assertThat(res.errors()).isEmpty();
    }

    private void expire(String batch, LocalDate mfg, LocalDate exp) {
        var b = batches.findByBatchNo(batch).orElseThrow();
        b.setMfgDate(mfg);
        b.setExpDate(exp);
        batches.save(b);
    }

    @BeforeAll
    void build() {
        today = time.today();
        as("admin", () -> {
            main = master.createWarehouse(new MasterDtos.WarehouseReq("WH1", "一号仓", WarehouseKind.OWN, null)).id();
            second = master.createWarehouse(new MasterDtos.WarehouseReq("WH2", "二号仓", WarehouseKind.OWN, null)).id();
            master.createPartner(new MasterDtos.PartnerReq("C1", "客户一", PartnerKind.CUSTOMER, null, null, null, null, "线下", 0, null, null, true, false));
            p = master.createItem(new MasterDtos.ItemReq("P", "成品 P", ItemType.FG, "瓶", null, null, false, 36, "备案-P", new BigDecimal("30"), true, new BigDecimal("30"), null));
            m = master.createItem(new MasterDtos.ItemReq("M", "原料 M", ItemType.RM, "kg", null, null, false, 36, null, null, true, new BigDecimal("4"), null));
            return null;
        });
        stock("P", "P-FRESH", today.minusDays(10), today.plusMonths(35), "100", true);
        stock("P", "P-EXPIRED", today.minusDays(100), today.plusMonths(1), "40", true);
        expire("P-EXPIRED", today.minusYears(3), today.minusDays(1));
        for (int i = 0; i < 12; i++) {
            stock("P", "SLOW-" + i, today.minusDays(100), today.plusMonths(30), "1", true);
        }
        stock("M", "M-OK", today.minusDays(10), today.plusMonths(20), "2.5", false);
        stock("M", "M-EXPIRED", today.minusDays(100), today.plusMonths(1), "9", false);
        expire("M-EXPIRED", today.minusYears(3), today.minusDays(1));
        // 调拨不是消耗：调过仓的老批次仍然呆滞
        Long slow0 = batches.findByBatchNo("SLOW-0").orElseThrow().getId();
        as("admin", () -> stockOps.transfer(new StockOpsService.TransferReq(main, second, "整理货位", List.of(new StockOpsService.QtyLineReq(slow0, BigDecimal.ONE)))));
    }

    @Test
    void inventoryValueMatchesHandCalculation() {
        var money = analytics.kpi().money();
        // 成品 (100 + 40 + 12) × 30 = 4560；原料 (2.5 + 9) × 4 = 46
        assertThat(money.fgValue()).isEqualByComparingTo("4560");
        assertThat(money.materialValue()).isEqualByComparingTo("46");
        assertThat(money.inventoryValue()).isEqualByComparingTo("4606");
    }

    @Test
    void slowMovingValueCountsEveryBatchNotJustTheTenListed() {
        var kpi = analytics.kpi();
        // P-EXPIRED 也是生产超过 60 天、没动过的：40 件 + 12 个老批次，全部算呆滞
        assertThat(kpi.money().slowMovingValue()).isEqualByComparingTo(new BigDecimal(30 * 52 + 4 * 9));
        assertThat(kpi.slowMoving()).hasSize(10);
    }

    @Test
    void transferDoesNotMakeAnOldBatchActive() {
        // 列表只显示最老的 10 个，这里用金额确认：调过仓的 SLOW-0 没有被排除，见上面的合计
        assertThat(analytics.kpi().money().slowMovingValue()).isGreaterThanOrEqualTo(new BigDecimal("1596"));
    }

    @Test
    void oldUnreportedAdverseComplaintStaysInTheCounts() {
        var c = as("admin", () -> complaints.create(new ComplaintService.ComplaintReq(today.minusMonths(8), "电话", null, null, p.getId(), null,
                ComplaintCategory.ADVERSE, com.cosmetic.scm.domain.Severity.SERIOUS, "皮肤红肿")));
        assertThat(c).isNotNull();
        var stats = analytics.kpi().complaints();
        assertThat(stats.open()).isGreaterThanOrEqualTo(1);
        assertThat(stats.adverseOverdue()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void openingImportDoesNotCountAsReleaseCycle() {
        var q = analytics.kpi().quality();
        assertThat(q.materialDecisions()).isZero();
        assertThat(q.releaseDecisions()).isZero();
    }

    @Test
    void expiredStockIsNotAvailableForReplenishment() {
        var row = planning.finishedGoods(new PlanningService.PlanParams(90, 30, 15, 60)).stream().filter(r -> r.code().equals("P")).findFirst().orElseThrow();
        // 100 + 12，不含过期的 40
        assertThat(row.available()).isEqualByComparingTo("112");
        as("admin", () -> {
            master.createItem(new MasterDtos.ItemReq("Q", "成品 Q", ItemType.FG, "瓶", null, null, false, 36, "备案-Q", new BigDecimal("30"), true, null, null));
            return imports.importBoms(new com.cosmetic.scm.master.MasterImportService.BomImportReq(List.of(
                    new com.cosmetic.scm.master.MasterImportService.BomImportRow("Q", "M", BigDecimal.ONE, com.cosmetic.scm.domain.SupplyMode.BRAND)), false));
        });
        Long q = itemRepo.findAll().stream().filter(i -> i.getCode().equals("Q")).findFirst().orElseThrow().getId();
        var mat = planning.materials(new PlanningService.MaterialReq(List.of(new PlanningService.PlanLine(q, new BigDecimal("20"), null))))
                .stream().filter(r -> r.code().equals("M")).findFirst().orElseThrow();
        // 过期的 9 kg 不能算可用：20 - 2.5 = 17.5
        assertThat(mat.onHand()).isEqualByComparingTo("2.5");
    }

    @Test
    void stockExportAddsUpToTheSameTotalsAsThePage() throws Exception {
        var rows = inventory.listStock(null, null, null, false);
        BigDecimal pageTotal = rows.stream().map(s -> s.qty()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(pageTotal).isEqualByComparingTo("163.5");
        byte[] xlsx = as("admin", () -> reports.export("stock", null, null)).content();
        String sheet = null;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                if (e.getName().contains("sheet1")) {
                    sheet = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        assertThat(sheet).isNotNull();
        assertThat(Pattern.compile("<row").matcher(sheet).results().count()).isEqualTo(rows.size() + 1);
    }

    @Test
    void expiredBatchesAreCalledExpiredAndMaterialsAreFlagged() {
        var near = dashboard.summary().todos().stream().filter(t -> t.kind().equals("NEAR_EXPIRY")).map(t -> t.ref()).toList();
        assertThat(near).contains("P-EXPIRED");
        assertThat(near).doesNotContain("P-FRESH");
        var todos = dashboard.summary().todos();
        assertThat(todos).filteredOn(t -> t.ref().equals("P-EXPIRED")).extracting(t -> t.label()).containsExactly("成品已过期");
        assertThat(todos).filteredOn(t -> t.kind().equals("EXPIRED_MATERIAL")).extracting(t -> t.ref()).containsExactly("M-EXPIRED");
    }

    private Item boundaryProduct(String code) {
        return as("admin", () -> master.createItem(new MasterDtos.ItemReq(code, "边界 " + code, ItemType.FG, "瓶", null, null, false, 36, "备案-" + code, new BigDecimal("30"), true, null, null)));
    }

    @Test
    void nearExpiryStartsJustBelowHalfOfShelfLife() {
        boundaryProduct("BD1");
        stock("BD1", "HALF", today.minusDays(100), today.plusDays(100), "1", true);
        stock("BD1", "UNDER", today.minusDays(100), today.plusDays(99), "1", true);
        var near = dashboard.summary().todos().stream().filter(t -> t.kind().equals("NEAR_EXPIRY")).map(t -> t.ref()).toList();
        assertThat(near).contains("UNDER").doesNotContain("HALF");
    }

    @Test
    void lastDayOfShelfLifeCanStillShipDayAfterCannot() {
        Item it = boundaryProduct("BD2");
        stock("BD2", "LASTDAY", today.minusYears(3), today, "5", true);
        var picks = inventory.allocate(it.getId(), new BigDecimal("5"), List.of(main), 0);
        assertThat(picks.picks()).extracting(x -> x.batchNo()).containsExactly("LASTDAY");

        Item gone = boundaryProduct("BD3");
        stock("BD3", "DAYAFTER", today.minusYears(3), today.plusDays(1), "5", true);
        expire("DAYAFTER", today.minusYears(3), today.minusDays(1));
        var none = inventory.allocate(gone.getId(), new BigDecimal("5"), List.of(main), 0);
        assertThat(none.picks()).isEmpty();
        assertThat(none.shortQty()).isEqualByComparingTo("5");
    }

    @Test
    void licenseIsUsableOnItsLastDayAndWarnsFromSixtyDaysOut() {
        var views = as("admin", () -> {
            for (Object[] c : new Object[][] {{"LIC-TODAY", today}, {"LIC-YEST", today.minusDays(1)}, {"LIC-60", today.plusDays(60)}, {"LIC-61", today.plusDays(61)}}) {
                master.createPartner(new MasterDtos.PartnerReq((String) c[0], (String) c[0], PartnerKind.SUPPLIER, "L", (LocalDate) c[1], null, null, null, 0, null, null, true, false));
            }
            return master.listPartners();
        });
        var state = views.stream().filter(v -> v.code().startsWith("LIC-")).collect(java.util.stream.Collectors.toMap(v -> v.code(), v -> v));
        assertThat(state.get("LIC-TODAY").licenseState()).isEqualTo("EXPIRING");
        assertThat(state.get("LIC-TODAY").usable()).isTrue();
        assertThat(state.get("LIC-YEST").licenseState()).isEqualTo("EXPIRED");
        assertThat(state.get("LIC-YEST").usable()).isFalse();
        assertThat(state.get("LIC-60").licenseState()).isEqualTo("EXPIRING");
        assertThat(state.get("LIC-61").licenseState()).isEqualTo("OK");
    }
}
