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

/** V52 主数据治理、采购执行预警、销售履约渠道概览与成品补货建议导出集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class V52MasterPoSoPlanTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String login(String u, String password) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", u, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void masterPurchaseSalesSummariesAndExportsWorkWithPriceIsolation() throws Exception {
        String admin = login("admin", "Admin@123456");
        String qa = login("qa1", PWD);

        // 1. 主数据资质风控与物料汇总
        mvc.perform(get("/api/master/summary").header("Authorization", "Bearer " + qa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeItems").isNumber())
                .andExpect(jsonPath("$.totalPartners").isNumber())
                .andExpect(jsonPath("$.bomsConfiguredCount").isNumber());

        // 2. 采购执行进度与交期预警汇总
        mvc.perform(get("/api/purchase-orders/summary").header("Authorization", "Bearer " + qa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").isNumber())
                .andExpect(jsonPath("$.overdueCount").isNumber())
                .andExpect(jsonPath("$.openShipmentsCount").isNumber())
                .andExpect(jsonPath("$.unresolvedDisputesCount").isNumber());

        // 3. 销售出库履约与渠道分布（管理员可见金额，QA 不可见金额）
        mvc.perform(get("/api/sales-orders/summary").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ordersInWindow").isNumber())
                .andExpect(jsonPath("$.amountInWindow").isNumber())
                .andExpect(jsonPath("$.byChannel").isArray());

        mvc.perform(get("/api/sales-orders/summary").header("Authorization", "Bearer " + qa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ordersInWindow").isNumber())
                .andExpect(jsonPath("$.amountInWindow").doesNotExist())
                .andExpect(jsonPath("$.byChannel").isArray());

        // 4. 新增 Excel 导出（items、partners、planning-fg，全员内部角色可导且内部按角色遮盖涉价字段）
        for (String key : new String[]{"items", "partners", "planning-fg"}) {
            mvc.perform(get("/api/reports/" + key).header("Authorization", "Bearer " + admin))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/reports/" + key).header("Authorization", "Bearer " + qa))
                    .andExpect(status().isOk());
        }
    }
}
