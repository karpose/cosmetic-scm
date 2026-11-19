package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.cosmetic.scm.demo.DemoDataLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** 应付/应收账龄分析与经销商门户应收对账接口测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgingAndDealerPortalTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String login(String u) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", u, "password", PWD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void agingAnalysisEndpointsAndExportsRespectPermissions() throws Exception {
        String fin = login("finance1");
        String qa = login("qa1");

        mvc.perform(get("/api/statements/aging").param("side", "AP").header("Authorization", "Bearer " + fin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.side").value("AP"))
                .andExpect(jsonPath("$.rows").isArray());

        mvc.perform(get("/api/statements/aging").param("side", "AR").header("Authorization", "Bearer " + fin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.side").value("AR"))
                .andExpect(jsonPath("$.rows").isArray());

        mvc.perform(get("/api/statements/aging").header("Authorization", "Bearer " + qa))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/reports/ap-aging").header("Authorization", "Bearer " + fin))
                .andExpect(status().isOk());
        mvc.perform(get("/api/reports/ar-aging").header("Authorization", "Bearer " + fin))
                .andExpect(status().isOk());
        mvc.perform(get("/api/reports/ap-aging").header("Authorization", "Bearer " + qa))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/reports/ar-aging").header("Authorization", "Bearer " + qa))
                .andExpect(status().isForbidden());
    }

    @Test
    void dealerPortalArStatementsIsolatedToDealerRole() throws Exception {
        String dealer = login("dealer1");
        String body = mvc.perform(get("/api/portal/dealer/ar-statements").header("Authorization", "Bearer " + dealer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode arr = json.readTree(body);
        assertThat(arr.isArray()).isTrue();
    }
}
