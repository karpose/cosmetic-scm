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

/** V49 库存效期风险看板、销售退货复盘、变更控制治理与标签设计稿合规概览集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class V49StockReturnsPlmOpsTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String login(String u) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", u, "password", PWD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void expiryRiskReturnsAnalyticsChangesAndArtworksSummariesWork() throws Exception {
        String qa = login("qa1");
        String fin = login("finance1");

        // 1. 库存效期风险看板（严格按 PriceAccess 隔离风险货值）
        mvc.perform(get("/api/analytics/expiry-risk").header("Authorization", "Bearer " + fin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canSeeValue").value(true))
                .andExpect(jsonPath("$.riskRows").isArray());
        mvc.perform(get("/api/analytics/expiry-risk").header("Authorization", "Bearer " + qa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canSeeValue").value(false))
                .andExpect(jsonPath("$.expiredValue").doesNotExist());

        // 2. 销售退货处置复盘
        mvc.perform(get("/api/analytics/sales-returns").header("Authorization", "Bearer " + qa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalReturns").isNumber())
                .andExpect(jsonPath("$.byCustomer").isArray())
                .andExpect(jsonPath("$.byItem").isArray());

        // 3. 变更控制治理概览
        mvc.perform(get("/api/changes/summary").header("Authorization", "Bearer " + qa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalChanges").isNumber())
                .andExpect(jsonPath("$.byType").isArray());

        // 4. 标签设计稿与签样合规概览
        mvc.perform(get("/api/artworks/summary").header("Authorization", "Bearer " + qa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalArtworks").isNumber())
                .andExpect(jsonPath("$.pendingProofCount").isNumber());

        // 5. 四项新增 Excel 导出
        for (String key : new String[]{"expiry-risk", "sales-returns", "changes", "artworks"}) {
            mvc.perform(get("/api/reports/" + key).header("Authorization", "Bearer " + qa))
                    .andExpect(status().isOk());
        }
    }
}
