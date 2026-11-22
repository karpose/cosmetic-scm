package com.cosmetic.scm;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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

/** V55 扫码核对近期流水接口集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class V55PortalsScanDocsTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String login(String u) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", u, "password", PWD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void scanRecentChecksReturnsRecordedCheck() throws Exception {
        String warehouse = login("purchase1");

        mvc.perform(post("/api/scan/checks").header("Authorization", "Bearer " + warehouse)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "refType", "SO",
                                "refNo", "SO-V55-TEST",
                                "expected", List.of("B-001"),
                                "scanned", List.of("B-001")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true));

        mvc.perform(get("/api/scan/recent").header("Authorization", "Bearer " + warehouse))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].refNo").value("SO-V55-TEST"))
                .andExpect(jsonPath("$[0].passed").value(true));
    }
}
