package com.cosmetic.scm;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import com.cosmetic.scm.demo.DemoDataLoader;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** V48 防伪异常扫码监控、客诉召回深度分析、系统集成健康度、上线检查与审计合规导出集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class V48SerialEventsOpsTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String login(String u) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", u, "password", PWD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void serialAnomaliesQualityEventsIntegrationHealthAndComplianceExportsWork() throws Exception {
        String qa = login("qa1");
        String sales = login("sales1");
        String fin = login("finance1");

        // 1. 一物一码防伪异常扫码监控
        mvc.perform(get("/api/serials/anomalies").param("minScans", "3")
                        .header("Authorization", "Bearer " + qa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.threshold").value(3))
                .andExpect(jsonPath("$.topAnomalies").isArray());

        // 2. 客诉、不良反应与召回效率深度分析
        mvc.perform(get("/api/analytics/quality-events").header("Authorization", "Bearer " + qa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalComplaints").isNumber())
                .andExpect(jsonPath("$.byCategory").isArray())
                .andExpect(jsonPath("$.byChannel").isArray())
                .andExpect(jsonPath("$.recalls").isArray());

        // 3. 系统接口集成健康度（非价格角色也可查看电商部分，FINANCE 日志由服务端自动过滤）
        mvc.perform(get("/api/channel-orders/health").param("days", "30")
                        .header("Authorization", "Bearer " + sales))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(30))
                .andExpect(jsonPath("$.byPlatform").isArray())
                .andExpect(jsonPath("$.topFailures").isArray());

        mvc.perform(get("/api/channel-orders/health").param("days", "7")
                        .header("Authorization", "Bearer " + fin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(7));

        // 4. V48 Excel 导出（含上线检查权限隔离与带筛选的审计导出）
        for (String key : new String[]{"serial-anomalies", "quality-events-summary", "integration-logs"}) {
            mvc.perform(get("/api/reports/" + key).header("Authorization", "Bearer " + qa))
                    .andExpect(status().isOk());
        }

        // 上线检查导出仅限 ADMIN/MASTER_DATA/QA/QSO
        mvc.perform(get("/api/reports/golive-checklist").header("Authorization", "Bearer " + qa))
                .andExpect(status().isOk());
        mvc.perform(get("/api/reports/golive-checklist").header("Authorization", "Bearer " + sales))
                .andExpect(status().isForbidden());

        // 审计日志带关键字与操作类型筛选导出
        mvc.perform(get("/api/reports/audit")
                        .param("q", "导出")
                        .param("action", "导出报表")
                        .header("Authorization", "Bearer " + qa))
                .andExpect(status().isOk());
    }
}
