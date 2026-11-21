package com.cosmetic.scm;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.ItemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** V47 PQR 年度质量回顾、受控文件治理、QMS 健康度与库存 ABC 分类集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class V47QualityGovernanceTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ItemRepository items;

    private String login(String u) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", u, "password", PWD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void pqrDocsQmsSummaryAndInventoryAbcWorkWithProperPermissions() throws Exception {
        String qa = login("qa1");
        String fin = login("finance1");
        Item fg = items.findAll().stream().filter(i -> i.getItemType() == ItemType.FG).findFirst().orElseThrow();

        // PQR 年度质量回顾
        mvc.perform(get("/api/analytics/pqr").param("fgItemId", String.valueOf(fg.getId()))
                        .header("Authorization", "Bearer " + qa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fgCode").value(fg.getCode()))
                .andExpect(jsonPath("$.batches").isArray());

        // 受控文件治理与 QMS 健康度
        mvc.perform(get("/api/docs/summary").header("Authorization", "Bearer " + qa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeDocs").isNumber());
        mvc.perform(get("/api/qms/summary").header("Authorization", "Bearer " + qa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDeviations").isNumber());

        // 库存 ABC 分类（严格按价格权限隔离）
        mvc.perform(get("/api/analytics/inventory-abc").header("Authorization", "Bearer " + fin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows").isArray());
        mvc.perform(get("/api/analytics/inventory-abc").header("Authorization", "Bearer " + qa))
                .andExpect(status().isForbidden());

        // Excel 导出报表
        for (String key : new String[]{"pqr", "controlled-docs", "qms-summary"}) {
            mvc.perform(get("/api/reports/" + key).header("Authorization", "Bearer " + qa))
                    .andExpect(status().isOk());
        }
        mvc.perform(get("/api/reports/inventory-abc").header("Authorization", "Bearer " + fin))
                .andExpect(status().isOk());
        mvc.perform(get("/api/reports/inventory-abc").header("Authorization", "Bearer " + qa))
                .andExpect(status().isForbidden());
    }
}
