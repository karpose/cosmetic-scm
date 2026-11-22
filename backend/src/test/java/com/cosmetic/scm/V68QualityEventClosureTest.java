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

import com.cosmetic.scm.complaint.ComplaintService;
import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.domain.ComplaintCategory;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.Severity;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterDtos;
import com.cosmetic.scm.master.MasterService;
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

/** V68 不良反应监管上报倒计时与召回闭环监控看板及报表导出集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-v68;MODE=MySQL;DB_CLOSE_DELAY=-1")
class V68QualityEventClosureTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MasterService master;
    @Autowired ComplaintService complaintService;

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
    void closureBoardTracksAdverseReportDeadlinesAndExportsReport() throws Exception {
        LocalDate today = LocalDate.now();
        Long fgId = as("admin", () -> {
            Item fg = master.createItem(new MasterDtos.ItemReq(
                    "FG-V68", "V68 修护精华液", ItemType.FG, "瓶", null, null, false, 36,
                    null, new BigDecimal("30"), true, new BigDecimal("25.00"), new BigDecimal("128.00")));
            return fg.getId();
        });

        // 1) 严重不良反应（7日内须上报），10天前受理 -> 已超期 3 天
        var overdueCp = as("qa1", () -> complaintService.create(new ComplaintService.ComplaintReq(
                today.minusDays(10), "电商平台", null, "陈女士", fgId, null,
                ComplaintCategory.ADVERSE, Severity.SERIOUS, "使用后出现明显红肿刺痛")));

        // 2) 严重不良反应（7日内须上报），5天前受理 -> 剩余 2 天（3天内临期）
        var dueSoonCp = as("qa1", () -> complaintService.create(new ComplaintService.ComplaintReq(
                today.minusDays(5), "线下门店", null, "林女士", fgId, null,
                ComplaintCategory.ADVERSE, Severity.SERIOUS, "涂抹后皮肤泛红起疹")));

        String qaToken = login("qa1");
        JsonNode board = json.readTree(mvc.perform(get("/api/analytics/quality-events/closure-board")
                        .header("Authorization", "Bearer " + qaToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(board.get("overdueCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(board.get("dueSoonCount").asInt()).isGreaterThanOrEqualTo(1);

        JsonNode overRow = findByRefNo(board.get("rows"), overdueCp.complaintNo());
        JsonNode soonRow = findByRefNo(board.get("rows"), dueSoonCp.complaintNo());
        assertThat(overRow).isNotNull();
        assertThat(overRow.get("riskLevel").asText()).isEqualTo("OVERDUE");
        assertThat(overRow.get("daysLeft").asLong()).isEqualTo(-3L);
        assertThat(overRow.get("itemType").asText()).isEqualTo("ADVERSE_REPORT");

        assertThat(soonRow).isNotNull();
        assertThat(soonRow.get("riskLevel").asText()).isEqualTo("DUE_SOON");
        assertThat(soonRow.get("daysLeft").asLong()).isEqualTo(2L);

        byte[] xlsx = mvc.perform(get("/api/reports/quality-event-closure").header("Authorization", "Bearer " + qaToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(xlsx.length).isGreaterThan(100);
    }

    private static JsonNode findByRefNo(JsonNode arr, String refNo) {
        for (JsonNode n : arr) {
            if (refNo.equals(n.get("refNo").asText())) {
                return n;
            }
        }
        return null;
    }
}
