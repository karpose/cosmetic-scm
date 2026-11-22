package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.MoStatus;
import com.cosmetic.scm.domain.MoType;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterDtos;
import com.cosmetic.scm.master.MasterService;
import com.cosmetic.scm.production.MfgIssue;
import com.cosmetic.scm.production.MfgIssueRepository;
import com.cosmetic.scm.production.MfgOrder;
import com.cosmetic.scm.production.MfgOrderRepository;
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

/** V66 生产批次成本异常波动监控、归因分析、价格权限隔离及报表导出集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-v66;MODE=MySQL;DB_CLOSE_DELAY=-1")
class V66BatchCostAnomalyTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MasterService master;
    @Autowired MfgOrderRepository mfgOrders;
    @Autowired MfgIssueRepository mfgIssues;

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
    void batchCostAnomalyClassifiesOverrunAndSavingAndEnforcesPriceAccess() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        var ids = as("admin", () -> {
            Item rm = master.createItem(new MasterDtos.ItemReq(
                    "RM-V66", "V66 测试原料", ItemType.RM, "kg", null, null, false, 24,
                    null, null, true, new BigDecimal("10.00"), null));
            Item fg = master.createItem(new MasterDtos.ItemReq(
                    "FG-V66", "V66 测试面霜", ItemType.FG, "瓶", null, null, false, 36,
                    null, new BigDecimal("50"), true, new BigDecimal("10.00"), new BigDecimal("68.00")));
            return new long[] {rm.getId(), fg.getId()};
        });

        MfgOrder sampleMo = mfgOrders.findAllByOrderByIdDesc().get(0);
        MfgIssue sampleIss = mfgIssues.findAll().get(0);

        // 1) 超标批次：计划 100 实际 80（收率 80% < 95%），领料成本 1000 -> 单位成本 12.5，偏差 +25.0%，超支 200 元
        MfgOrder overrunMo = new MfgOrder();
        overrunMo.setMoNo("MO-V66-OVER");
        overrunMo.setMoType(MoType.OWN);
        overrunMo.setFactoryId(sampleMo.getFactoryId());
        overrunMo.setFgItemId(ids[1]);
        overrunMo.setPlannedQty(new BigDecimal("100"));
        overrunMo.setActualQty(new BigDecimal("80"));
        overrunMo.setPlannedDate(LocalDate.now());
        overrunMo.setStatus(MoStatus.COMPLETED);
        overrunMo.setCreatedBy("prod1");
        overrunMo.setCreatedAt(now.minusHours(2));
        overrunMo.setCompletedAt(now);
        overrunMo = mfgOrders.save(overrunMo);

        MfgIssue overIss = new MfgIssue();
        overIss.setMoId(overrunMo.getId());
        overIss.setBatchId(sampleIss.getBatchId());
        overIss.setWarehouseId(sampleIss.getWarehouseId());
        overIss.setItemId(ids[0]);
        overIss.setQty(new BigDecimal("100"));
        overIss.setIssueType("INITIAL");
        overIss.setCreatedBy("prod1");
        overIss.setCreatedAt(now.minusHours(1));
        mfgIssues.save(overIss);

        // 2) 节约批次：计划 100 实际 100，领料成本 800 -> 单位成本 8.0，偏差 -20.0%，节约 200 元
        MfgOrder savingMo = new MfgOrder();
        savingMo.setMoNo("MO-V66-SAVE");
        savingMo.setMoType(MoType.OWN);
        savingMo.setFactoryId(sampleMo.getFactoryId());
        savingMo.setFgItemId(ids[1]);
        savingMo.setPlannedQty(new BigDecimal("100"));
        savingMo.setActualQty(new BigDecimal("100"));
        savingMo.setPlannedDate(LocalDate.now());
        savingMo.setStatus(MoStatus.COMPLETED);
        savingMo.setCreatedBy("prod1");
        savingMo.setCreatedAt(now.minusHours(2));
        savingMo.setCompletedAt(now);
        savingMo = mfgOrders.save(savingMo);

        MfgIssue saveIss = new MfgIssue();
        saveIss.setMoId(savingMo.getId());
        saveIss.setBatchId(sampleIss.getBatchId());
        saveIss.setWarehouseId(sampleIss.getWarehouseId());
        saveIss.setItemId(ids[0]);
        saveIss.setQty(new BigDecimal("80"));
        saveIss.setIssueType("INITIAL");
        saveIss.setCreatedBy("prod1");
        saveIss.setCreatedAt(now.minusHours(1));
        mfgIssues.save(saveIss);

        String finToken = login("finance1");
        JsonNode rows = json.readTree(mvc.perform(get("/api/analytics/batch-costs")
                        .header("Authorization", "Bearer " + finToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        JsonNode overRow = findByMoNo(rows, "MO-V66-OVER");
        JsonNode saveRow = findByMoNo(rows, "MO-V66-SAVE");
        assertThat(overRow).isNotNull();
        assertThat(overRow.get("anomalyLevel").asText()).isEqualTo("OVERRUN");
        assertThat(overRow.get("variancePct").decimalValue()).isEqualByComparingTo("25.0");
        assertThat(overRow.get("varianceAmount").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(overRow.get("yieldPct").decimalValue()).isEqualByComparingTo("80.0");
        assertThat(overRow.get("costDriver").asText()).contains("收率偏低");

        assertThat(saveRow).isNotNull();
        assertThat(saveRow.get("anomalyLevel").asText()).isEqualTo("SAVING");
        assertThat(saveRow.get("variancePct").decimalValue()).isEqualByComparingTo("-20.0");
        assertThat(saveRow.get("varianceAmount").decimalValue()).isEqualByComparingTo("-200.00");

        JsonNode summary = json.readTree(mvc.perform(get("/api/analytics/batch-costs/summary")
                        .header("Authorization", "Bearer " + finToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(summary.get("overrunCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(summary.get("savingCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(summary.get("totalOverrunAmount").decimalValue()).isGreaterThanOrEqualTo(new BigDecimal("200.00"));

        // 财务可导出批次成本 Excel，无价格权限的生产岗位必须被 403 拦截
        byte[] xlsx = mvc.perform(get("/api/reports/batch-costs").header("Authorization", "Bearer " + finToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(xlsx.length).isGreaterThan(100);

        String prodToken = login("prod1");
        mvc.perform(get("/api/analytics/batch-costs/summary").header("Authorization", "Bearer " + prodToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/reports/batch-costs").header("Authorization", "Bearer " + prodToken))
                .andExpect(status().isForbidden());
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
