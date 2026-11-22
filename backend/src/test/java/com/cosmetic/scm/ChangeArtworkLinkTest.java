package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 打开“设计稿改版要先有批准的变更”开关。用单独的库，开关不影响别的测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"scm.change.require-for-artwork=true", "spring.datasource.url=jdbc:h2:mem:scm-artchange;MODE=MySQL;DB_CLOSE_DELAY=-1"})
class ChangeArtworkLinkTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;
    private static final List<String> ALL = List.of("INGREDIENTS", "FILING", "NET", "USAGE", "WARNING", "COMPANY", "BATCH");

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

    private JsonNode read(String t, String url) throws Exception {
        return ok(mvc.perform(get(url).header("Authorization", "Bearer " + t)));
    }

    private long item(String admin, String code, String type) throws Exception {
        Map<String, Object> m = new java.util.HashMap<>(Map.of("code", code, "name", "物料" + code, "itemType", type, "unit", "RM".equals(type) ? "kg" : "个", "banned", false, "active", true, "shelfMonths", 24));
        if ("FG".equals(type)) {
            m.put("netG", 50);
            m.put("filingNo", "备案" + code);
            m.put("unit", "瓶");
        }
        return ok(call(admin, "/api/items", m)).get("id").asLong();
    }

    private long change(String raiser, String qa, String qso, String type, boolean approve) throws Exception {
        long id = ok(call(raiser, "/api/changes", Map.of("title", "改标签", "changeType", type, "reason", "x", "description", "x"))).get("change").get("id").asLong();
        if (approve) {
            ok(call(qa, "/api/changes/" + id + "/assess", Map.of("risk", "LOW", "impact", "x", "needFiling", false, "needStability", false, "needLabel", false, "needValidation", false)));
            ok(call(qso, "/api/changes/" + id + "/approve", Map.of("password", PWD)));
        }
        return id;
    }

    private void approve(String qa, String qso, long art) throws Exception {
        mvc.perform(multipart("/api/attachments").file(new MockMultipartFile("file", "art.pdf", "application/pdf", "%PDF-1.4 art".getBytes(StandardCharsets.UTF_8)))
                .param("ownerType", "LABEL_ARTWORK").param("ownerId", String.valueOf(art)).header("Authorization", "Bearer " + qa)).andExpect(status().isOk());
        ok(call(qso, "/api/artworks/" + art + "/approve", Map.of("checked", ALL, "password", PWD)));
    }

    @Test
    void artworkRevisionsNeedAnApprovedLabelChangeAndTheChangeCannotCloseWhileTheArtworkIsPending() throws Exception {
        String admin = login("admin", "Admin@123456");
        String qa = login("qa1", PWD);
        String qso = login("qso1", PWD);
        long rm = item(admin, "AC-RM", "RM");
        long pk = item(admin, "AC-PK", "PK");
        long fg = item(admin, "AC-FG", "FG");
        mvc.perform(put("/api/boms/" + fg).header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("lines", List.of(
                Map.of("itemId", rm, "qtyPer", new BigDecimal("0.01"), "supplyMode", "BRAND"), Map.of("itemId", pk, "qtyPer", BigDecimal.ONE, "supplyMode", "BRAND")))))).andExpect(status().isOk());

        // 首版不是改版，不用关联
        long v1 = ok(call(qa, "/api/artworks", Map.of("pkItemId", pk, "fgItemId", fg, "changeNote", "首版"))).get("id").asLong();
        approve(qa, qso, v1);

        // 改版：没关联、关联了别的类型、关联了没批准的，都不行
        call(qa, "/api/artworks", Map.of("pkItemId", pk, "fgItemId", fg, "changeNote", "改警示语")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("改版要先关联")));
        long process = change(qa, qa, qso, "PROCESS", true);
        call(qa, "/api/artworks", Map.of("pkItemId", pk, "fgItemId", fg, "changeNote", "改警示语", "changeId", process)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("只能关联标签、包材或配方变更")));
        long draft = change(qa, qa, qso, "LABEL", false);
        call(qa, "/api/artworks", Map.of("pkItemId", pk, "fgItemId", fg, "changeNote", "改警示语", "changeId", draft)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("批准后才能关联设计稿")));
        long label = change(qa, qa, qso, "LABEL", true);
        JsonNode opts = read(qa, "/api/changes/artwork-options");
        assertThat(opts.get("required").asBoolean()).isTrue();
        assertThat(opts.get("changes").toString()).contains("\"id\":" + label).doesNotContain("\"id\":" + process + ",").doesNotContain("\"id\":" + draft + ",");
        JsonNode v2 = ok(call(qa, "/api/artworks", Map.of("pkItemId", pk, "fgItemId", fg, "changeNote", "改警示语", "changeId", label)));
        assertThat(v2.get("versionNo").asInt()).isEqualTo(2);
        String changeNo = read(qa, "/api/changes/" + label).get("change").get("changeNo").asText();
        assertThat(v2.get("changeNo").asText()).isEqualTo(changeNo);

        // 关联的设计稿没批准，变更不能关闭；批准后可以
        JsonNode detail = read(qa, "/api/changes/" + label);
        assertThat(detail.get("artworks").get(0).get("artworkNo").asText()).isEqualTo(v2.get("artworkNo").asText());
        String boardState = "$.rows[?(@.changeId == " + label + ")].closureState";
        mvc.perform(get("/api/changes/closure-board").header("Authorization", "Bearer " + qa))
                .andExpect(status().isOk())
                .andExpect(jsonPath(boardState, org.hamcrest.Matchers.contains("PENDING_LINKED_APPROVAL")));
        call(qso, "/api/changes/" + label + "/close", Map.of("note", "完成", "password", PWD)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString(v2.get("artworkNo").asText())));
        approve(qa, qso, v2.get("id").asLong());
        mvc.perform(get("/api/changes/closure-board").header("Authorization", "Bearer " + qa))
                .andExpect(status().isOk())
                .andExpect(jsonPath(boardState, org.hamcrest.Matchers.contains("READY_TO_CLOSE")));
        ok(call(qso, "/api/changes/" + label + "/close", Map.of("note", "完成", "password", PWD)));
        // 关闭后不能再关联
        call(qa, "/api/artworks", Map.of("pkItemId", pk, "fgItemId", fg, "changeNote", "再改", "changeId", label)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("不能再关联设计稿")));
        assertThat(read(admin, "/api/audit?q=" + v2.get("artworkNo").asText()).toString()).contains("关联变更 " + changeNo);
    }
}
