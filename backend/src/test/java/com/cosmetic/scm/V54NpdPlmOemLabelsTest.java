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

/** V54 新品研发治理看板、配方备案汇总及台账导出集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class V54NpdPlmOemLabelsTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String login(String u) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", u, "password", PWD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void npdAndFormulaSummariesAndExportsWork() throws Exception {
        String qa = login("qa1");

        // 1. 新品研发治理汇总
        mvc.perform(get("/api/npd/summary").header("Authorization", "Bearer " + qa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").isNumber())
                .andExpect(jsonPath("$.activeCount").isNumber())
                .andExpect(jsonPath("$.lateCount").isNumber());

        // 2. 配方与备案治理汇总
        mvc.perform(get("/api/plm/formulas/summary").header("Authorization", "Bearer " + qa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeFgCount").isNumber())
                .andExpect(jsonPath("$.effectiveFormulaFgCount").isNumber())
                .andExpect(jsonPath("$.filedCount").isNumber());

        // 3. 新品项目与备案台账 Excel 导出
        for (String key : new String[]{"npd-projects", "filings"}) {
            mvc.perform(get("/api/reports/" + key).header("Authorization", "Bearer " + qa))
                    .andExpect(status().isOk());
        }
    }
}
