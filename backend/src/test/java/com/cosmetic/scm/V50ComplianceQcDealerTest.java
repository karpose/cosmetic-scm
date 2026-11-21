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

/** V50 产品合规风控、稳定性覆盖治理、质量放行效率与经销商订货履约集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class V50ComplianceQcDealerTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String login(String u) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", u, "password", PWD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void complianceStabilityQualityAndDealerSummariesAndExportsWork() throws Exception {
        String qa = login("qa1");
        String sales = login("sales1");

        // 1. 产品合规与法规目录风控汇总
        mvc.perform(get("/api/compliance/summary").header("Authorization", "Bearer " + qa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalWithFormula").isNumber())
                .andExpect(jsonPath("$.violationCount").isNumber());

        // 2. 稳定性试验进度与保质期依据覆盖汇总
        mvc.perform(get("/api/stability/summary").header("Authorization", "Bearer " + qa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalStudies").isNumber())
                .andExpect(jsonPath("$.totalProducts").isNumber());

        // 3. 质量检验与上市放行效率汇总
        mvc.perform(get("/api/quality/summary").header("Authorization", "Bearer " + qa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quarantineCount").isNumber())
                .andExpect(jsonPath("$.decisions30dTotal").isNumber());

        // 4. 经销商订货履约汇总
        mvc.perform(get("/api/dealer-orders/summary").header("Authorization", "Bearer " + sales))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalOrders").isNumber())
                .andExpect(jsonPath("$.submittedCount").isNumber());

        // 5. 五项新增 Excel 导出
        for (String key : new String[]{
                "compliance-status", "regulatory-violations", "stability-coverage",
                "release-decisions", "dealer-orders"}) {
            mvc.perform(get("/api/reports/" + key).header("Authorization", "Bearer " + qa))
                    .andExpect(status().isOk());
        }
    }
}
