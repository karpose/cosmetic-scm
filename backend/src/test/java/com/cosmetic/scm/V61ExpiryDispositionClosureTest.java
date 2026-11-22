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
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.inventory.OpeningStockService;
import com.cosmetic.scm.master.MasterDtos;
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

/** V61 效期风险分级处置建议、总风险货值权限隔离与未冻结过期计数集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-v61;MODE=MySQL;DB_CLOSE_DELAY=-1")
class V61ExpiryDispositionClosureTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MasterService master;
    @Autowired OpeningStockService opening;
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
    void expiryRiskProvidesRecommendedActionsAndEnforcesPriceAccess() throws Exception {
        LocalDate today = LocalDate.now();
        as("admin", () -> {
            master.createItem(new MasterDtos.ItemReq(
                    "FG-V61", "V61 面霜", ItemType.FG, "瓶", null, null, false, 24,
                    "备案-V61", new BigDecimal("50"), true, new BigDecimal("40.00"), new BigDecimal("120.00")));
            return null;
        });
        as("qso1", () -> {
            var row = new OpeningStockService.OpeningRow(
                    "FG-V61", "LOT-V61-EXP", null, today.minusYears(2), today.plusDays(1),
                    "WH-MAIN", new BigDecimal("10"), BatchStatus.RELEASED, null, "RPT-V61", 2, "留样柜");
            assertThat(opening.run(new OpeningStockService.OpeningReq(List.of(row), false, null),
                    users.findByUsername("qso1").orElseThrow()).errors()).isEmpty();
            return null;
        });
        Batch expBatch = batches.findByBatchNo("LOT-V61-EXP").orElseThrow();
        expBatch.setExpDate(today.minusDays(2));
        batches.save(expBatch);

        String finToken = login("finance1");
        String qaToken = login("qa1");

        JsonNode finRep = json.readTree(mvc.perform(get("/api/analytics/expiry-risk")
                        .header("Authorization", "Bearer " + finToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(finRep.get("canSeeValue").asBoolean()).isTrue();
        assertThat(finRep.get("totalRiskValue").decimalValue()).isGreaterThanOrEqualTo(new BigDecimal("400"));
        assertThat(finRep.get("expiredUnfrozenCount").asLong()).isGreaterThanOrEqualTo(1);
        JsonNode row = findRow(finRep.get("riskRows"), "LOT-V61-EXP");
        assertThat(row).isNotNull();
        assertThat(row.get("riskLevel").asText()).isEqualTo("EXPIRED");
        assertThat(row.get("recommendedAction").asText()).contains("报废");
        assertThat(row.get("actionRoute").asText()).isEqualTo("/inventory-ops");

        JsonNode qaRep = json.readTree(mvc.perform(get("/api/analytics/expiry-risk")
                        .header("Authorization", "Bearer " + qaToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(qaRep.get("canSeeValue").asBoolean()).isFalse();
        assertThat(qaRep.get("totalRiskValue").isNull()).isTrue();

        byte[] xlsx = mvc.perform(get("/api/reports/expiry-risk").header("Authorization", "Bearer " + finToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(xlsx.length).isGreaterThan(100);
    }

    private static JsonNode findRow(JsonNode arr, String batchNo) {
        for (JsonNode n : arr) {
            if (batchNo.equals(n.get("batchNo").asText())) {
                return n;
            }
        }
        return null;
    }
}
