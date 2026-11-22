package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.SupplyMode;
import com.cosmetic.scm.domain.WarehouseKind;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.inventory.GenealogyRepository;
import com.cosmetic.scm.inventory.OpeningStockService;
import com.cosmetic.scm.inventory.StockRepository;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterDtos;
import com.cosmetic.scm.master.MasterImportService;
import com.cosmetic.scm.master.MasterService;
import com.cosmetic.scm.security.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** V57 委外寄售料账核销 + 生产补料（超耗领料）+ 余料退库集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-v57;MODE=MySQL;DB_CLOSE_DELAY=-1")
class V57OemConsignmentIssueOpsTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MasterService master;
    @Autowired MasterImportService imports;
    @Autowired OpeningStockService opening;
    @Autowired BatchRepository batches;
    @Autowired StockRepository stocks;
    @Autowired GenealogyRepository genealogy;
    @Autowired UserRepository users;

    private <T> T as(String user, Supplier<T> s) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        try {
            return s.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private String login(String u) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", u, "password", PWD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void oemConsignmentTransferSupplementReturnAndWriteOffLifecycle() throws Exception {
        LocalDate today = LocalDate.now();
        Long ownWh = master.listWarehouses().stream().filter(w -> "WH-MAIN".equals(w.code())).findFirst().orElseThrow().id();

        var ctx = as("admin", () -> {
            Long oemId = master.createPartner(new MasterDtos.PartnerReq(
                    "OEM-V57", "V57 测试受托厂", PartnerKind.OEM, "LIC-V57", today.plusYears(2),
                    "护肤类", "V57", null, 0, null, null, true, false)).id();
            Long oemWhId = master.createWarehouse(new MasterDtos.WarehouseReq(
                    "WH-OEM-V57", "V57 受托厂寄存仓", WarehouseKind.OEM, oemId)).id();
            Item rm = master.createItem(new MasterDtos.ItemReq(
                    "RM-V57", "V57 玻尿酸原液", ItemType.RM, "kg", null, null, false, 24,
                    null, null, true, new BigDecimal("100.00"), null));
            Item fg = master.createItem(new MasterDtos.ItemReq(
                    "FG-V57", "V57 精华液", ItemType.FG, "瓶", null, null, false, 36,
                    "备案-V57", new BigDecimal("30"), true, new BigDecimal("20.00"), null));
            imports.importBoms(new MasterImportService.BomImportReq(List.of(
                    new MasterImportService.BomImportRow("FG-V57", "RM-V57", new BigDecimal("0.1"), SupplyMode.BRAND)), false));
            return new long[] {oemId, oemWhId, rm.getId(), fg.getId()};
        });
        long oemId = ctx[0];
        long oemWhId = ctx[1];
        long rmId = ctx[2];
        long fgId = ctx[3];

        as("qso1", () -> {
            var row = new OpeningStockService.OpeningRow(
                    "RM-V57", "LOT-V57-RM", "SUP-V57", today.minusDays(10), today.plusMonths(18),
                    "WH-MAIN", new BigDecimal("50"), BatchStatus.RELEASED, null, "COA-V57", null, null);
            assertThat(opening.run(new OpeningStockService.OpeningReq(List.of(row), false, null),
                    users.findByUsername("qso1").orElseThrow()).errors()).isEmpty();
            return null;
        });
        Batch rmBatch = batches.findByBatchNo("LOT-V57-RM").orElseThrow();

        String prodToken = login("prod1");
        String finToken = login("finance1");

        // 1. 创建委托生产单（计划 100 瓶，理论需料 10 kg）并首次发料
        JsonNode created = json.readTree(mvc.perform(post("/api/mfg-orders")
                        .header("Authorization", "Bearer " + prodToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("factoryId", oemId, "fgItemId", fgId, "plannedQty", 100))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        long moId = created.get("id").asLong();

        mvc.perform(post("/api/mfg-orders/" + moId + "/issue")
                        .header("Authorization", "Bearer " + prodToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"));

        // 校验发料后：详情显示委外寄存仓，且寄售料账中该批余料为 10 kg
        mvc.perform(get("/api/mfg-orders/" + moId).header("Authorization", "Bearer " + prodToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.oemWarehouseName").value("V57 受托厂寄存仓"))
                .andExpect(jsonPath("$.requirements[0].issuedQty").value(10))
                .andExpect(jsonPath("$.issueLogs.length()").value(1))
                .andExpect(jsonPath("$.issueLogs[0].issueType").value("INITIAL"));

        // 权限校验：prod1 查寄售料账不含金额，finance1 可见单价与货值（10 * 100 = 1000）
        JsonNode prodConsign = json.readTree(mvc.perform(get("/api/mfg-orders/oem-consignment")
                        .header("Authorization", "Bearer " + prodToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode prodRow = findConsignmentRow(prodConsign, "LOT-V57-RM");
        assertThat(prodRow).isNotNull();
        assertThat(prodRow.get("qty").decimalValue()).isEqualByComparingTo("10");
        assertThat(prodRow.get("unitCost").isNull()).isTrue();
        assertThat(prodRow.get("value").isNull()).isTrue();

        JsonNode finConsign = json.readTree(mvc.perform(get("/api/mfg-orders/oem-consignment")
                        .header("Authorization", "Bearer " + finToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode finRow = findConsignmentRow(finConsign, "LOT-V57-RM");
        assertThat(finRow).isNotNull();
        assertThat(finRow.get("unitCost").decimalValue()).isEqualByComparingTo("100");
        assertThat(finRow.get("value").decimalValue()).isEqualByComparingTo("1000");

        // 2. 生产补料（超耗领料 2 kg）→ 净发料 12 kg，寄售仓余料 12 kg
        mvc.perform(post("/api/mfg-orders/" + moId + "/supplement")
                        .header("Authorization", "Bearer " + prodToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("itemId", rmId, "qty", 2, "reason", "乳化锅挂壁损耗补料"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requirements[0].issuedQty").value(12))
                .andExpect(jsonPath("$.issueLogs.length()").value(2))
                .andExpect(jsonPath("$.issueLogs[1].issueType").value("SUPPLEMENT"));

        // 3. 生产余料退库：超额退库（15 kg > 12 kg）应拒绝；正常退库 3 kg → 净发料 9 kg，寄售仓余料 9 kg
        mvc.perform(post("/api/mfg-orders/" + moId + "/return-material")
                        .header("Authorization", "Bearer " + prodToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "batchId", rmBatch.getId(), "warehouseId", ownWh, "qty", 15, "reason", "超额测试"))))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/mfg-orders/" + moId + "/return-material")
                        .header("Authorization", "Bearer " + prodToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "batchId", rmBatch.getId(), "warehouseId", ownWh, "qty", 3, "reason", "未拆封余料退回"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requirements[0].issuedQty").value(9))
                .andExpect(jsonPath("$.issueLogs.length()").value(3))
                .andExpect(jsonPath("$.issueLogs[2].issueType").value("RETURN"));

        // 此时寄售仓余料应为 9 kg，自有仓余料应为 50 - 10 - 2 + 3 = 41 kg
        BigDecimal oemBeforeComplete = stocks.findByBatchId(rmBatch.getId()).stream()
                .filter(s -> s.getWarehouseId().equals(oemWhId)).map(s -> s.getQty()).findFirst().orElse(BigDecimal.ZERO);
        BigDecimal ownBeforeComplete = stocks.findByBatchId(rmBatch.getId()).stream()
                .filter(s -> s.getWarehouseId().equals(ownWh)).map(s -> s.getQty()).findFirst().orElse(BigDecimal.ZERO);
        assertThat(oemBeforeComplete).isEqualByComparingTo("9");
        assertThat(ownBeforeComplete).isEqualByComparingTo("41");

        // 导出委外寄售料账 Excel 应返回 200 且非空
        byte[] xlsx = mvc.perform(get("/api/reports/oem-consignment").header("Authorization", "Bearer " + finToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(xlsx.length).isGreaterThan(100);

        // 4. 完工入库 → 自动核销寄售仓 9 kg 归零，谱系记录净用量 9 kg
        JsonNode completed = json.readTree(mvc.perform(post("/api/mfg-orders/" + moId + "/complete")
                        .header("Authorization", "Bearer " + prodToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "actualQty", 100, "mfgDate", today.toString(),
                                "warehouseId", ownWh, "oemReleased", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andReturn().getResponse().getContentAsString());

        BigDecimal oemAfterComplete = stocks.findByBatchId(rmBatch.getId()).stream()
                .filter(s -> s.getWarehouseId().equals(oemWhId)).map(s -> s.getQty()).findFirst().orElse(BigDecimal.ZERO);
        assertThat(oemAfterComplete).isEqualByComparingTo("0");

        String outBatchNo = completed.get("outBatchNo").asText();
        Batch outBatch = batches.findByBatchNo(outBatchNo).orElseThrow();
        var gens = genealogy.findByChildBatchId(outBatch.getId());
        assertThat(gens).hasSize(1);
        assertThat(gens.get(0).getQty()).isEqualByComparingTo("9");
    }

    private static JsonNode findConsignmentRow(JsonNode array, String batchNo) {
        for (JsonNode n : array) {
            if (batchNo.equals(n.get("batchNo").asText())) {
                return n;
            }
        }
        return null;
    }
}
