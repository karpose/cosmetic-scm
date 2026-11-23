package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.domain.FilingType;
import com.cosmetic.scm.plm.NpdProject;
import com.cosmetic.scm.plm.NpdProjectRepository;
import com.cosmetic.scm.plm.NpdService;
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

/** V76 新品研发（NPD）上市门禁缺口与超期风险监控看板及报表导出集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-v76;MODE=MySQL;DB_CLOSE_DELAY=-1")
class V76NpdRiskBoardTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TimeProvider time;
    @Autowired NpdService npdService;
    @Autowired NpdProjectRepository npdRepo;

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
    void riskBoardClassifiesOverdueAtRiskAndPendingApprovalAndExportsReport() throws Exception {
        LocalDate today = time.today();

        // 1) 开发中且已超计划上市日 5 天 -> OVERDUE
        var overdueDetail = as("qa1", () -> npdService.create(new NpdService.CreateReq(
                "V76 舒缓修护乳", "乳液", FilingType.GENERAL, "舒缓保湿", today.plusDays(1), "V76 超期测试")));
        as("admin", () -> npdService.approve(overdueDetail.project().id()));
        NpdProject overdueEntity = npdRepo.findById(overdueDetail.project().id()).orElseThrow();
        overdueEntity.setTargetLaunch(today.minusDays(5));
        npdRepo.save(overdueEntity);

        // 2) 开发中、距上市 15 天且未关联成品/门禁缺口 -> AT_RISK
        var atRiskDetail = as("qa1", () -> npdService.create(new NpdService.CreateReq(
                "V76 轻透防晒霜", "防晒", FilingType.SPECIAL, "高倍防晒", today.plusDays(15), "V76 临期缺口测试")));
        as("admin", () -> npdService.approve(atRiskDetail.project().id()));

        // 3) 待管理员立项审批 -> PENDING_APPROVAL
        var proposedDetail = as("qa1", () -> npdService.create(new NpdService.CreateReq(
                "V76 夜间精华油", "精华", FilingType.GENERAL, "抗皱紧致", today.plusDays(60), "V76 待立项测试")));

        String qaToken = login("qa1");
        JsonNode board = json.readTree(mvc.perform(get("/api/npd/risk-board")
                        .header("Authorization", "Bearer " + qaToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(board.get("overdueCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(board.get("atRiskCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(board.get("pendingApprovalCount").asInt()).isGreaterThanOrEqualTo(1);

        JsonNode overRow = findByProjectNo(board.get("rows"), overdueDetail.project().projectNo());
        JsonNode riskRow = findByProjectNo(board.get("rows"), atRiskDetail.project().projectNo());
        JsonNode propRow = findByProjectNo(board.get("rows"), proposedDetail.project().projectNo());
        assertThat(overRow).isNotNull();
        assertThat(overRow.get("riskLevel").asText()).isEqualTo("OVERDUE");
        assertThat(overRow.get("daysLeft").asLong()).isEqualTo(-5L);

        assertThat(riskRow).isNotNull();
        assertThat(riskRow.get("riskLevel").asText()).isEqualTo("AT_RISK");
        assertThat(riskRow.get("daysLeft").asLong()).isEqualTo(15L);

        assertThat(propRow).isNotNull();
        assertThat(propRow.get("riskLevel").asText()).isEqualTo("PENDING_APPROVAL");

        byte[] xlsx = mvc.perform(get("/api/reports/npd-risk").header("Authorization", "Bearer " + qaToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(xlsx.length).isGreaterThan(100);
    }

    private static JsonNode findByProjectNo(JsonNode arr, String projectNo) {
        for (JsonNode n : arr) {
            if (projectNo.equals(n.get("projectNo").asText())) {
                return n;
            }
        }
        return null;
    }
}
