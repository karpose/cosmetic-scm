package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.ItemRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** V46 PLM 进阶能力集成测试：配方版本对比、PIF 档案聚合、原料反查、NPD 就绪度与 Excel 导出。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlmInsightsTest {

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
    void pifWhereUsedReadinessAndVersionCompareWork() throws Exception {
        String qa = login("qa1");
        String fin = login("finance1");

        Item fg = items.findAll().stream().filter(i -> i.getItemType() == ItemType.FG).findFirst().orElseThrow();
        Item rm = items.findAll().stream().filter(i -> i.getItemType() == ItemType.RM).findFirst().orElseThrow();

        // PIF 档案聚合
        mvc.perform(get("/api/plm/pif/" + fg.getId()).header("Authorization", "Bearer " + qa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fgCode").value(fg.getCode()))
                .andExpect(jsonPath("$.formulaLines").isArray());

        // 原料反查 Where-Used
        mvc.perform(get("/api/plm/where-used/" + rm.getId()).header("Authorization", "Bearer " + qa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // 全线合规就绪度 & NPD 上市就绪度
        mvc.perform(get("/api/plm/readiness").header("Authorization", "Bearer " + qa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        mvc.perform(get("/api/npd/readiness").header("Authorization", "Bearer " + qa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // 配方版本对比（价格权限隔离：QA 不返回成本，财务返回成本或 null）
        String versionsJson = mvc.perform(get("/api/plm/formulas").header("Authorization", "Bearer " + qa))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode versions = json.readTree(versionsJson);
        assertThat(versions.size()).isGreaterThan(0);
        long vId = versions.get(0).get("id").asLong();

        String qaDiff = mvc.perform(get("/api/plm/formulas/compare")
                        .param("fromVersionId", String.valueOf(vId))
                        .param("toVersionId", String.valueOf(vId))
                        .header("Authorization", "Bearer " + qa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines").isArray())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(json.readTree(qaDiff).get("fromMaterialCost").isNull()).isTrue();

        mvc.perform(get("/api/plm/formulas/compare")
                        .param("fromVersionId", String.valueOf(vId))
                        .param("toVersionId", String.valueOf(vId))
                        .header("Authorization", "Bearer " + fin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines").isArray());

        // 3 张 PLM Excel 导出报表
        for (String key : new String[]{"plm-readiness", "where-used", "npd-readiness"}) {
            mvc.perform(get("/api/reports/" + key).header("Authorization", "Bearer " + qa))
                    .andExpect(status().isOk());
        }
    }
}
