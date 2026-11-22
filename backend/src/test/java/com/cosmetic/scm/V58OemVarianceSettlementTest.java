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
import com.cosmetic.scm.domain.Decision;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.SupplyMode;
import com.cosmetic.scm.domain.WarehouseKind;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.inventory.OpeningStockService;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterDtos;
import com.cosmetic.scm.master.MasterImportService;
import com.cosmetic.scm.master.MasterService;
import com.cosmetic.scm.quality.QualityService;
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

/** V58 委外物料超耗平衡台账 + 委外加工费对账超耗扣款联动集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-v58;MODE=MySQL;DB_CLOSE_DELAY=-1")
class V58OemVarianceSettlementTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MasterService master;
    @Autowired MasterImportService imports;
    @Autowired OpeningStockService opening;
    @Autowired QualityService quality;
    @Autowired BatchRepository batches;
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
    void oemMaterialOveruseSurfacesInVarianceLedgerAndSettlementPreview() throws Exception {
        LocalDate today = LocalDate.now();
        Long ownWh = master.listWarehouses().stream().filter(w -> "WH-MAIN".equals(w.code())).findFirst().orElseThrow().id();

        var ctx = as("admin", () -> {
            Long oemId = master.createPartner(new MasterDtos.PartnerReq(
                    "OEM-V58", "V58 受托厂", PartnerKind.OEM, "LIC-V58", today.plusYears(2),
                    "护肤类", "V58", null, 0, null, null, true, false)).id();
            master.createWarehouse(new MasterDtos.WarehouseReq("WH-OEM-V58", "V58 寄存仓", WarehouseKind.OEM, oemId));
            Item rm = master.createItem(new MasterDtos.ItemReq(
                    "RM-V58", "V58 胜肽原液", ItemType.RM, "kg", null, null, false, 24,
                    null, null, true, new BigDecimal("200.00"), null));
            Item fg = master.createItem(new MasterDtos.ItemReq(
                    "FG-V58", "V58 抗皱精华", ItemType.FG, "瓶", null, null, false, 36,
                    "备案-V58", new BigDecimal("30"), true, new BigDecimal("50.00"), null));
            imports.importBoms(new MasterImportService.BomImportReq(List.of(
                    new MasterImportService.BomImportRow("FG-V58", "RM-V58", new BigDecimal("0.1"), SupplyMode.BRAND)), false));
            return new long[] {oemId, rm.getId(), fg.getId()};
        });
        long oemId = ctx[0];
        long rmId = ctx[1];
        long fgId = ctx[2];

        as("qso1", () -> {
            var row = new OpeningStockService.OpeningRow(
                    "RM-V58", "LOT-V58-RM", "SUP-V58", today.minusDays(10), today.plusMonths(18),
                    "WH-MAIN", new BigDecimal("50"), BatchStatus.RELEASED, null, "COA-V58", null, null);
            assertThat(opening.run(new OpeningStockService.OpeningReq(List.of(row), false, null),
                    users.findByUsername("qso1").orElseThrow()).errors()).isEmpty();
            return null;
        });

        String prodToken = login("prod1");
        String finToken = login("finance1");

        // 1. 建委托单（计划 100 瓶，标耗 10 kg）→ 发料 10 kg → 超耗补料 1.5 kg → 完工 100 瓶
        JsonNode created = json.readTree(mvc.perform(post("/api/mfg-orders")
                        .header("Authorization", "Bearer " + prodToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("factoryId", oemId, "fgItemId", fgId, "plannedQty", 100))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        long moId = created.get("id").asLong();
        String moNo = created.get("moNo").asText();

        mvc.perform(post("/api/mfg-orders/" + moId + "/issue").header("Authorization", "Bearer " + prodToken))
                .andExpect(status().isOk());

        mvc.perform(post("/api/mfg-orders/" + moId + "/supplement")
                        .header("Authorization", "Bearer " + prodToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("itemId", rmId, "qty", 1.5, "reason", "管道残留超耗补料"))))
                .andExpect(status().isOk());

        mvc.perform(post("/api/mfg-orders/" + moId + "/processing-fee")
                        .header("Authorization", "Bearer " + finToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("processingUnitFee", 12, "processingTaxRate", 13))))
                .andExpect(status().isOk());

        JsonNode completed = json.readTree(mvc.perform(post("/api/mfg-orders/" + moId + "/complete")
                        .header("Authorization", "Bearer " + prodToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "actualQty", 100, "mfgDate", today.toString(),
                                "warehouseId", ownWh, "oemReleased", true))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String outBatchNo = completed.get("outBatchNo").asText();
        Long outBatchId = batches.findByBatchNo(outBatchNo).orElseThrow().getId();

        // 2. 上市放行，使该委托单进入委外加工费可结算范围
        as("qso1", () -> quality.marketRelease(outBatchId, new QualityService.ReleaseReq(
                Decision.RELEASED, "RPT-V58", "检验合格放行", true, true, true, true, 2, "留样柜", null),
                users.findByUsername("qso1").orElseThrow()));

        // 3. 校验物料超耗平衡台账与 PriceAccess 权限隔离（超耗 1.5 kg，偏差 +15.0%，折合 1.5 * 200 = 300 元）
        JsonNode prodVars = json.readTree(mvc.perform(get("/api/mfg-orders/material-variance")
                        .header("Authorization", "Bearer " + prodToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode prodRow = findByMoNo(prodVars, moNo);
        assertThat(prodRow).isNotNull();
        assertThat(prodRow.get("state").asText()).isEqualTo("OVERUSE");
        assertThat(prodRow.get("varianceQty").decimalValue()).isEqualByComparingTo("1.5");
        assertThat(prodRow.get("variancePct").decimalValue()).isEqualByComparingTo("15.0");
        assertThat(prodRow.get("varianceCost").isNull()).isTrue();

        JsonNode finVars = json.readTree(mvc.perform(get("/api/mfg-orders/material-variance")
                        .header("Authorization", "Bearer " + finToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode finRow = findByMoNo(finVars, moNo);
        assertThat(finRow).isNotNull();
        assertThat(finRow.get("varianceCost").decimalValue()).isEqualByComparingTo("300.00");

        // 4. 校验委外加工费对账预览联动超耗提示与建议扣款（货款 100 * 12 = 1200，建议扣款 300）
        mvc.perform(get("/api/statements/preview")
                        .param("supplierId", String.valueOf(oemId))
                        .param("periodTo", today.toString())
                        .header("Authorization", "Bearer " + finToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goodsAmount").value(1200))
                .andExpect(jsonPath("$.oemOveruses.length()").value(1))
                .andExpect(jsonPath("$.oemOveruses[0].moNo").value(moNo))
                .andExpect(jsonPath("$.oemOveruses[0].overuseQty").value(1.5))
                .andExpect(jsonPath("$.oemOveruses[0].overuseAmount").value(300))
                .andExpect(jsonPath("$.suggestedDeduction").value(300));

        // 5. 导出物料超耗平衡台账 Excel
        byte[] xlsx = mvc.perform(get("/api/reports/material-variance").header("Authorization", "Bearer " + finToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(xlsx.length).isGreaterThan(100);
    }

    private static JsonNode findByMoNo(JsonNode array, String moNo) {
        for (JsonNode n : array) {
            if (moNo.equals(n.get("moNo").asText())) {
                return n;
            }
        }
        return null;
    }
}
