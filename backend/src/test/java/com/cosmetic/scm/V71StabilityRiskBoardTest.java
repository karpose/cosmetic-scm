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
import com.cosmetic.scm.domain.FormulaStatus;
import com.cosmetic.scm.domain.StabilityType;
import com.cosmetic.scm.plm.FormulaVersion;
import com.cosmetic.scm.plm.FormulaVersionRepository;
import com.cosmetic.scm.plm.StabilityService;
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

/** V71 稳定性试验取样倒计时、保质期覆盖缺口监控看板及报表导出集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-v71;MODE=MySQL;DB_CLOSE_DELAY=-1")
class V71StabilityRiskBoardTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired StabilityService stabilityService;
    @Autowired FormulaVersionRepository formulaVersions;

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
    void riskBoardTracksOverdueAndDueSoonPullsAndExportsReport() throws Exception {
        LocalDate today = LocalDate.now();
        FormulaVersion effective = formulaVersions.findByStatus(FormulaStatus.EFFECTIVE).get(0);

        // 10天前开始的试验，设两个时间点：+5天（已超期 5 天）与 +13天（3天后到期）
        var study = as("qa1", () -> stabilityService.create(new StabilityService.CreateReq(
                effective.getFgItemId(), StabilityType.ACCELERATED, effective.getId(),
                null, "V71-SAMPLE-01", "40℃±2℃ / 75%RH", today.minusDays(10),
                List.of(new StabilityService.TimepointReq(0, 5), new StabilityService.TimepointReq(0, 13)),
                List.of(new StabilityService.TestReq("pH", null, new BigDecimal("5.0"), new BigDecimal("7.0"), null)),
                "V71 预警测试")));

        String qaToken = login("qa1");
        JsonNode board = json.readTree(mvc.perform(get("/api/stability/risk-board")
                        .header("Authorization", "Bearer " + qaToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(board.get("overduePullCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(board.get("dueSoonPullCount").asInt()).isGreaterThanOrEqualTo(1);

        JsonNode overdueRow = findByRefAndRisk(board.get("rows"), study.study().studyNo(), "OVERDUE");
        JsonNode dueSoonRow = findByRefAndRisk(board.get("rows"), study.study().studyNo(), "DUE_SOON");
        assertThat(overdueRow).isNotNull();
        assertThat(overdueRow.get("daysLeft").asLong()).isEqualTo(-5L);
        assertThat(dueSoonRow).isNotNull();
        assertThat(dueSoonRow.get("daysLeft").asLong()).isEqualTo(3L);

        byte[] xlsx = mvc.perform(get("/api/reports/stability-risk").header("Authorization", "Bearer " + qaToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(xlsx.length).isGreaterThan(100);
    }

    private static JsonNode findByRefAndRisk(JsonNode arr, String refNo, String riskLevel) {
        for (JsonNode n : arr) {
            if (refNo.equals(n.get("refNo").asText()) && riskLevel.equals(n.get("riskLevel").asText())) {
                return n;
            }
        }
        return null;
    }
}
