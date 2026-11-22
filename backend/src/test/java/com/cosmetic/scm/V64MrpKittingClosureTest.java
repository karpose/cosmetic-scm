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
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.SupplyMode;
import com.cosmetic.scm.inventory.OpeningStockService;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterDtos;
import com.cosmetic.scm.master.MasterImportService;
import com.cosmetic.scm.master.MasterService;
import com.cosmetic.scm.production.ProductionService;
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

/** V64 待发料生产/委托单齐套预检（按计划日期先后占料）与报表导出集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-v64;MODE=MySQL;DB_CLOSE_DELAY=-1")
class V64MrpKittingClosureTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MasterService master;
    @Autowired MasterImportService imports;
    @Autowired OpeningStockService opening;
    @Autowired ProductionService production;
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
    void kittingCheckAllocatesByPlannedDateAndExportsReport() throws Exception {
        LocalDate today = LocalDate.now();
        var ctx = as("admin", () -> {
            Long oemId = master.createPartner(new MasterDtos.PartnerReq(
                    "OEM-V64", "V64 受托厂", PartnerKind.OEM, "LIC-V64", today.plusYears(2),
                    "护肤", "V64", null, 0, null, null, true, false)).id();
            master.createItem(new MasterDtos.ItemReq(
                    "RM-V64", "V64 活性肽", ItemType.RM, "kg", null, null, false, 24,
                    null, null, true, new BigDecimal("100"), null));
            Item fg = master.createItem(new MasterDtos.ItemReq(
                    "FG-V64", "V64 修护乳", ItemType.FG, "瓶", null, null, false, 36,
                    "备案-V64", new BigDecimal("50"), true, new BigDecimal("40"), null));
            imports.importBoms(new MasterImportService.BomImportReq(List.of(
                    new MasterImportService.BomImportRow("FG-V64", "RM-V64", new BigDecimal("0.1"), SupplyMode.BRAND)), false));
            return new long[] {oemId, fg.getId()};
        });

        // 只有 12 kg 可用库存：足够第一张工单（需 10 kg），第二张工单（需 10 kg）缺 8 kg
        as("qso1", () -> {
            var row = new OpeningStockService.OpeningRow(
                    "RM-V64", "LOT-V64-RM", null, today.minusDays(5), today.plusMonths(18),
                    "WH-MAIN", new BigDecimal("12"), BatchStatus.RELEASED, null, "COA-V64", null, null);
            assertThat(opening.run(new OpeningStockService.OpeningReq(List.of(row), false, null),
                    users.findByUsername("qso1").orElseThrow()).errors()).isEmpty();
            return null;
        });

        var mo1 = as("prod1", () -> production.create(new ProductionService.CreateMoReq(ctx[0], ctx[1], new BigDecimal("100"), today)));
        var mo2 = as("prod1", () -> production.create(new ProductionService.CreateMoReq(ctx[0], ctx[1], new BigDecimal("100"), today.plusDays(3))));

        String prodToken = login("prod1");
        JsonNode list = json.readTree(mvc.perform(get("/api/planning/kitting")
                        .header("Authorization", "Bearer " + prodToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        JsonNode r1 = findByMoNo(list, mo1.moNo());
        JsonNode r2 = findByMoNo(list, mo2.moNo());
        assertThat(r1).isNotNull();
        assertThat(r1.get("status").asText()).isEqualTo("READY");
        assertThat(r1.get("kittingPct").decimalValue()).isEqualByComparingTo("100.0");

        assertThat(r2).isNotNull();
        assertThat(r2.get("status").asText()).isEqualTo("SHORTAGE");
        assertThat(r2.get("shortages").size()).isEqualTo(1);
        assertThat(r2.get("shortages").get(0).get("shortQty").decimalValue()).isEqualByComparingTo("8");

        byte[] xlsx = mvc.perform(get("/api/reports/planning-kitting").header("Authorization", "Bearer " + prodToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(xlsx.length).isGreaterThan(100);
    }

    private static JsonNode findByMoNo(JsonNode arr, String moNo) {
        for (JsonNode n : arr) {
            if (moNo.equals(n.get("moNo").asText())) {
                return n;
            }
        }
        return null;
    }
}
