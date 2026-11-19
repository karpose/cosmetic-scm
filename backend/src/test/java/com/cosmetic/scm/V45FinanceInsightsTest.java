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

/** V45 毛利分析、现金流预测与库存盘点报废损益估值及权限隔离集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class V45FinanceInsightsTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String login(String u) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", u, "password", PWD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void financeInsightsEndpointsAndExportsRespectPriceAccess() throws Exception {
        String fin = login("finance1");
        String qa = login("qa1");

        mvc.perform(get("/api/analytics/margins").header("Authorization", "Bearer " + fin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byItem").isArray())
                .andExpect(jsonPath("$.byCustomer").isArray());
        mvc.perform(get("/api/analytics/margins").header("Authorization", "Bearer " + qa))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/analytics/cashflow-forecast").header("Authorization", "Bearer " + fin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buckets.length()").value(5));
        mvc.perform(get("/api/analytics/cashflow-forecast").header("Authorization", "Bearer " + qa))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/analytics/inventory-variance").header("Authorization", "Bearer " + fin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows").isArray());
        mvc.perform(get("/api/analytics/inventory-variance").header("Authorization", "Bearer " + qa))
                .andExpect(status().isForbidden());

        for (String key : new String[]{"margins", "cashflow-forecast", "inventory-variance"}) {
            mvc.perform(get("/api/reports/" + key).header("Authorization", "Bearer " + fin))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/reports/" + key).header("Authorization", "Bearer " + qa))
                    .andExpect(status().isForbidden());
        }
    }
}
