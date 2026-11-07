package com.cosmetic.scm;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.inventory.BatchRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 开关打开后，安全评估和标签没签批的不能上市放行。默认关闭：一上线就拦，所有现存产品都放不了行。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"scm.compliance.require-docs-for-release=true", "spring.datasource.url=jdbc:h2:mem:scm-gate;MODE=MySQL;DB_CLOSE_DELAY=-1"})
class ComplianceReleaseGateTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired BatchRepository batches;
    @Autowired TimeProvider time;

    private String login(String u, String p) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", u, "password", p)))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    private ResultActions call(String t, String url, Object body) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void releaseNeedsSignedSafetyAndLabelWhenTheSwitchIsOn() throws Exception {
        String admin = login("admin", "Admin@123456");
        String qso = login("qso1", PWD);
        String qa = login("qa1", PWD);
        long rm = body(call(admin, "/api/items", Map.of("code", "GT-RM", "name", "门禁原料", "itemType", "RM", "unit", "kg", "banned", false, "active", true, "shelfMonths", 36)).andExpect(status().isOk())).get("id").asLong();
        long fg = body(call(admin, "/api/items", Map.of("code", "GT-FG", "name", "门禁成品", "itemType", "FG", "unit", "瓶", "banned", false, "active", true, "shelfMonths", 36, "netG", 100, "filingNo", "备案GT")).andExpect(status().isOk())).get("id").asLong();
        mvc.perform(put("/api/boms/" + fg).header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("lines", List.of(Map.of("itemId", rm, "qtyPer", new BigDecimal("0.01"), "supplyMode", "BRAND")))))).andExpect(status().isOk());
        JsonNode opening = body(call(qso, "/api/opening-stock", Map.of("dryRun", false, "password", PWD, "rows", List.of(Map.of(
                "itemCode", "GT-FG", "batchNo", "GT-B1", "status", "PENDING_RELEASE", "warehouseCode", "WH-MAIN", "qty", 10, "mfgDate", time.today().minusDays(5).toString())))).andExpect(status().isOk()));
        org.assertj.core.api.Assertions.assertThat(opening.get("applied").asBoolean()).isTrue();
        long batch = batches.findByBatchNo("GT-B1").orElseThrow().getId();

        Map<String, Object> release = new HashMap<>(Map.of("decision", "RELEASED", "reportNo", "R1", "note", "放行", "inspectionPassed", true, "recordsComplete", true,
                "consistentWithFiling", true, "retainQty", 1, "retainLocation", "留样柜", "password", PWD));
        call(qso, "/api/quality/batches/" + batch + "/market-release", release).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("安全评估"))).andExpect(jsonPath("$.message", containsString("标签")));

        // 只签了安全评估：还差标签
        long safety = body(call(qa, "/api/compliance/docs", Map.of("fgItemId", fg, "docType", "SAFETY", "title", "安全评估", "evaluator", "机构", "docDate", time.today().toString())).andExpect(status().isOk())).get("id").asLong();
        call(qso, "/api/compliance/docs/" + safety + "/approve", Map.of("password", PWD)).andExpect(status().isOk());
        call(qso, "/api/quality/batches/" + batch + "/market-release", release).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("标签"))).andExpect(jsonPath("$.message", not(containsString("安全评估"))));

        // 待签批的不算
        long label = body(call(qa, "/api/compliance/docs", Map.of("fgItemId", fg, "docType", "LABEL", "title", "标签", "evaluator", "质量部", "docDate", time.today().toString())).andExpect(status().isOk())).get("id").asLong();
        call(qso, "/api/quality/batches/" + batch + "/market-release", release).andExpect(status().isBadRequest());
        call(qso, "/api/compliance/docs/" + label + "/approve", Map.of("password", PWD)).andExpect(status().isOk());
        call(qso, "/api/quality/batches/" + batch + "/market-release", release).andExpect(status().isOk());
    }
}
