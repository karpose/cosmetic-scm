package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.inventory.OpeningStockService;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterDtos;
import com.cosmetic.scm.master.MasterImportService;
import com.cosmetic.scm.master.MasterService;
import com.cosmetic.scm.production.ProductionService;
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

/** V63 首页工作台全链路风险待办贯通（不合格品待处置、生产超耗预警、且不泄露价格）集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-v63;MODE=MySQL;DB_CLOSE_DELAY=-1")
class V63DashboardRiskTodosTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MasterService master;
    @Autowired MasterImportService imports;
    @Autowired OpeningStockService opening;
    @Autowired QualityService quality;
    @Autowired ProductionService production;
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
    void dashboardSurfacesNcrAndMaterialOveruseTodosWithoutLeakingPrices() throws Exception {
        LocalDate today = LocalDate.now();
        var ctx = as("admin", () -> {
            Long oemId = master.createPartner(new MasterDtos.PartnerReq(
                    "OEM-V63", "V63 测试厂", PartnerKind.OEM, "LIC-V63", today.plusYears(2),
                    "护肤", "V63", null, 0, null, null, true, false)).id();
            Item rm = master.createItem(new MasterDtos.ItemReq(
                    "RM-V63", "V63 原料", ItemType.RM, "kg", null, null, false, 24,
                    null, null, true, new BigDecimal("500.00"), null));
            Item fg = master.createItem(new MasterDtos.ItemReq(
                    "FG-V63", "V63 精华", ItemType.FG, "瓶", null, null, false, 36,
                    "备案-V63", new BigDecimal("30"), true, new BigDecimal("60.00"), null));
            imports.importBoms(new MasterImportService.BomImportReq(List.of(
                    new MasterImportService.BomImportRow("FG-V63", "RM-V63", new BigDecimal("0.1"), SupplyMode.BRAND)), false));
            return new long[] {oemId, rm.getId(), fg.getId()};
        });

        as("qso1", () -> {
            var ok = new OpeningStockService.OpeningRow(
                    "RM-V63", "LOT-V63-OK", null, today.minusDays(5), today.plusMonths(18),
                    "WH-MAIN", new BigDecimal("30"), BatchStatus.RELEASED, null, "COA-V63", null, null);
            var bad = new OpeningStockService.OpeningRow(
                    "RM-V63", "LOT-V63-BAD", null, today.minusDays(5), today.plusMonths(18),
                    "WH-MAIN", new BigDecimal("5"), BatchStatus.QUARANTINE, null, null, null, null);
            assertThat(opening.run(new OpeningStockService.OpeningReq(List.of(ok, bad), false, null),
                    users.findByUsername("qso1").orElseThrow()).errors()).isEmpty();
            return null;
        });

        // 1. 制造一个不合格在库批次
        Batch badBatch = batches.findByBatchNo("LOT-V63-BAD").orElseThrow();
        as("qa1", () -> quality.materialDecision(badBatch.getId(), new QualityService.MaterialDecisionReq(
                Decision.REJECTED, "QC-V63", "检验不合格", PWD), users.findByUsername("qa1").orElseThrow()));

        // 2. 制造一个超耗补料的在制工单
        as("prod1", () -> {
            var mo = production.create(new ProductionService.CreateMoReq(ctx[0], ctx[2], new BigDecimal("100"), today));
            production.issue(mo.id());
            production.supplement(mo.id(), new ProductionService.SupplementReq(ctx[1], new BigDecimal("1"), "超耗补料测试"));
            return null;
        });

        String qaToken = login("qa1");
        JsonNode dash = json.readTree(mvc.perform(get("/api/dashboard")
                        .header("Authorization", "Bearer " + qaToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        JsonNode todos = dash.get("todos");
        JsonNode ncrTodo = findByKind(todos, "NCR_DISPOSITION");
        assertThat(ncrTodo).isNotNull();
        assertThat(ncrTodo.get("route").asText()).isEqualTo("/quality?tab=ncr");

        JsonNode overuseTodo = findByKind(todos, "MATERIAL_OVERUSE");
        assertThat(overuseTodo).isNotNull();
        assertThat(overuseTodo.get("route").asText()).isEqualTo("/production?tab=variance");
        // 确保不含任何价格/金额符号
        assertThat(overuseTodo.toString()).doesNotContain("¥").doesNotContain("500");
    }

    private static JsonNode findByKind(JsonNode arr, String kind) {
        for (JsonNode n : arr) {
            if (kind.equals(n.get("kind").asText())) {
                return n;
            }
        }
        return null;
    }
}
