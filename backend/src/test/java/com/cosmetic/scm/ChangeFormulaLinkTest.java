package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 打开“配方变更要先有批准的变更”开关。用单独的库，开关不影响别的测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"scm.change.require-for-formula=true", "spring.datasource.url=jdbc:h2:mem:scm-changelink;MODE=MySQL;DB_CLOSE_DELAY=-1"})
class ChangeFormulaLinkTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String login(String u, String p) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", u, "password", p)))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    private ResultActions call(String t, String url, Object body) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private JsonNode ok(ResultActions r) throws Exception {
        return json.readTree(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private long change(String raiser, String qa, String qso, String type, boolean approve) throws Exception {
        long id = ok(call(raiser, "/api/changes", Map.of("title", "调整用量", "changeType", type, "reason", "x", "description", "x"))).get("change").get("id").asLong();
        if (approve) {
            ok(call(qa, "/api/changes/" + id + "/assess", Map.of("risk", "LOW", "impact", "x", "needFiling", false, "needStability", false, "needLabel", false, "needValidation", false)));
            ok(call(qso, "/api/changes/" + id + "/approve", Map.of("password", PWD)));
        }
        return id;
    }

    @Test
    void formulaDraftsNeedAnApprovedFormulaChangeAndTheChangeCannotCloseWhileTheDraftIsOpen() throws Exception {
        String admin = login("admin", "Admin@123456");
        String qa = login("qa1", PWD);
        String qso = login("qso1", PWD);
        String sales = login("sales1", PWD);
        long m = ok(call(admin, "/api/items", Map.of("code", "CL-RM", "name", "原料", "itemType", "RM", "unit", "kg", "banned", false, "active", true, "shelfMonths", 24))).get("id").asLong();
        long f = ok(call(admin, "/api/items", Map.of("code", "CL-FG", "name", "成品", "itemType", "FG", "unit", "瓶", "banned", false, "active", true, "shelfMonths", 24, "netG", 50, "filingNo", "备案CL"))).get("id").asLong();
        // 首版配方走主数据，不受开关影响
        mvc.perform(put("/api/boms/" + f).header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("lines", List.of(Map.of("itemId", m, "qtyPer", new BigDecimal("0.01"), "supplyMode", "BRAND")))))).andExpect(status().isOk());
        Map<String, Object> draft = new HashMap<>(Map.of("changeNote", "调整用量", "lines", List.of(Map.of("itemId", m, "qtyPer", new BigDecimal("0.02"), "supplyMode", "BRAND"))));
        String url = "/api/plm/formulas/" + f + "/draft";

        call(admin, url, draft).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("先关联")));
        draft.put("changeId", change(sales, qa, qso, "FORMULA", false));
        call(admin, url, draft).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("批准后")));
        draft.put("changeId", change(sales, qa, qso, "DOCUMENT", true));
        call(admin, url, draft).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("只能关联")));

        long cid = change(sales, qa, qso, "FORMULA", true);
        JsonNode opts = ok(mvc.perform(get("/api/changes/formula-options").header("Authorization", "Bearer " + admin)));
        assertThat(opts.get("required").asBoolean()).isTrue();
        assertThat(opts.get("changes").toString()).contains("\"id\":" + cid);
        draft.put("changeId", cid);
        JsonNode v = ok(call(admin, url, draft));
        assertThat(v.get("changeNo").asText()).startsWith("CC");
        JsonNode cd = ok(mvc.perform(get("/api/changes/" + cid).header("Authorization", "Bearer " + qa)));
        assertThat(cd.get("formulas").get(0).get("fgCode").asText()).isEqualTo("CL-FG");
        assertThat(cd.get("formulas").get(0).get("status").asText()).isEqualTo("DRAFT");

        // 配方还在草稿：变更关不了；放弃草稿后能关；关了以后不能再挂配方
        call(qso, "/api/changes/" + cid + "/close", Map.of("note", "x", "password", PWD)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("CL-FG V2")));
        ok(call(admin, "/api/plm/versions/" + v.get("id").asLong() + "/discard", Map.of()));
        ok(call(qso, "/api/changes/" + cid + "/close", Map.of("note", "方案取消", "password", PWD)));
        call(admin, url, draft).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("已关闭")));
    }
}
