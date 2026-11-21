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

/** V51 生产效率对比、质量标准治理、退供流转时效、库存作业与账号权限台账集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class V51ProdSpecsOpsTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String login(String u) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", u, "password", PWD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void productionEfficiencySpecsSummarySupplierReturnsAndAdminExportsWork() throws Exception {
        String admin = login("admin");
        String qa = login("qa1");

        // 1. 生产收率与工厂效率对比
        mvc.perform(get("/api/analytics/production-efficiency").header("Authorization", "Bearer " + qa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalOrders").isNumber())
                .andExpect(jsonPath("$.byFactory").isArray())
                .andExpect(jsonPath("$.byItem").isArray());

        // 2. 质量标准覆盖率与检验合格率汇总
        mvc.perform(get("/api/quality/specs/summary").header("Authorization", "Bearer " + qa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeItemsTotal").isNumber())
                .andExpect(jsonPath("$.inspections30dTotal").isNumber());

        // 3. 退供流转时效与供应商排行汇总
        mvc.perform(get("/api/supplier-returns/summary").header("Authorization", "Bearer " + qa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").isNumber())
                .andExpect(jsonPath("$.bySupplier").isArray());

        // 4. 新增 Excel 导出（其中 users 仅限系统管理员）
        for (String key : new String[]{"production-efficiency", "quality-specs", "inventory-ops"}) {
            mvc.perform(get("/api/reports/" + key).header("Authorization", "Bearer " + qa))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/reports/users").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        mvc.perform(get("/api/reports/users").header("Authorization", "Bearer " + qa))
                .andExpect(status().isForbidden());
    }
}
