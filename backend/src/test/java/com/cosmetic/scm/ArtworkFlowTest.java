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
import java.util.List;
import java.util.Map;

import com.cosmetic.scm.common.TimeProvider;
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

/** 标签设计稿评审。用单独的库。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-artwork;MODE=MySQL;DB_CLOSE_DELAY=-1")
class ArtworkFlowTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;
    private static final List<String> ALL = List.of("INGREDIENTS", "FILING", "NET", "USAGE", "WARNING", "COMPANY", "BATCH");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TimeProvider time;

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

    private long item(String admin, String code, String type) throws Exception {
        Map<String, Object> m = new java.util.HashMap<>(Map.of("code", code, "name", "物料" + code, "itemType", type, "unit", "RM".equals(type) ? "kg" : "个", "banned", false, "active", true, "shelfMonths", 24));
        if ("FG".equals(type)) {
            m.put("netG", 50);
            m.put("filingNo", "备案" + code);
            m.put("unit", "瓶");
        }
        return ok(call(admin, "/api/items", m)).get("id").asLong();
    }

    private void upload(String t, long id) throws Exception {
        mvc.perform(multipart("/api/attachments").file(new MockMultipartFile("file", "art.pdf", "application/pdf", "%PDF-1.4 art".getBytes(StandardCharsets.UTF_8)))
                .param("ownerType", "LABEL_ARTWORK").param("ownerId", String.valueOf(id)).header("Authorization", "Bearer " + t)).andExpect(status().isOk());
    }

    @Test
    void artworkNeedsEveryCheckAFileAnotherQsoAndGoesStaleWhenTheFormulaChanges() throws Exception {
        String admin = login("admin", "Admin@123456");
        String qa = login("qa1", PWD);
        String qso = login("qso1", PWD);
        long rm = item(admin, "AW-RM", "RM");
        long pk = item(admin, "AW-PK", "PK");
        long other = item(admin, "AW-PK2", "PK");
        long fg = item(admin, "AW-FG", "FG");
        mvc.perform(put("/api/boms/" + fg).header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("lines", List.of(
                Map.of("itemId", rm, "qtyPer", new BigDecimal("0.01"), "supplyMode", "BRAND"), Map.of("itemId", pk, "qtyPer", BigDecimal.ONE, "supplyMode", "BRAND")))))).andExpect(status().isOk());

        call(qa, "/api/artworks", Map.of("pkItemId", other, "fgItemId", fg, "changeNote", "x")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不在")));
        call(qa, "/api/artworks", Map.of("pkItemId", rm, "fgItemId", fg, "changeNote", "x")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不是包材")));
        call(login("purchase1", PWD), "/api/artworks", Map.of("pkItemId", pk, "fgItemId", fg, "changeNote", "x")).andExpect(status().isForbidden());
        JsonNode v1 = ok(call(qa, "/api/artworks", Map.of("pkItemId", pk, "fgItemId", fg, "changeNote", "首版")));
        long id1 = v1.get("id").asLong();
        assertThat(v1.get("versionNo").asInt()).isEqualTo(1);
        call(qa, "/api/artworks", Map.of("pkItemId", pk, "fgItemId", fg, "changeNote", "又一版")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("待签批")));
        assertThat(ok(mvc.perform(get("/api/dashboard").header("Authorization", "Bearer " + qso))).get("todos").toString()).contains("标签设计稿待评审");

        // 只有 QSO；评审项要全勾；要有文件
        call(qa, "/api/artworks/" + id1 + "/approve", Map.of("checked", ALL, "password", PWD)).andExpect(status().isForbidden());
        call(qso, "/api/artworks/" + id1 + "/approve", Map.of("checked", List.of("INGREDIENTS"), "password", PWD)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("警示用语")));
        call(qso, "/api/artworks/" + id1 + "/approve", Map.of("checked", ALL, "password", PWD)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("设计稿文件")));
        upload(qa, id1);
        JsonNode a1 = ok(call(qso, "/api/artworks/" + id1 + "/approve", Map.of("checked", ALL, "password", PWD)));
        assertThat(a1.get("status").asText()).isEqualTo("APPROVED");
        assertThat(a1.get("checked").size()).isEqualTo(7);

        // 提交人不能自己批
        long own = ok(call(qso, "/api/artworks", Map.of("pkItemId", pk, "fgItemId", fg, "changeNote", "第二版"))).get("id").asLong();
        upload(qso, own);
        call(qso, "/api/artworks/" + own + "/approve", Map.of("checked", ALL, "password", PWD)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("提交人")));
        ok(call(qso, "/api/artworks/" + own + "/reject", Map.of("note", "换人提交")));

        // 第 3 版批准，第 1 版替代
        long id3 = ok(call(qa, "/api/artworks", Map.of("pkItemId", pk, "fgItemId", fg, "changeNote", "改了警示语"))).get("id").asLong();
        upload(qa, id3);
        ok(call(qso, "/api/artworks/" + id3 + "/approve", Map.of("checked", ALL, "password", PWD)));
        String list = ok(mvc.perform(get("/api/artworks").header("Authorization", "Bearer " + qa))).toString();
        assertThat(list).contains("SUPERSEDED").contains("\"checklist\"");

        // 第 4 版提交后配方变了：第 3 版要重新评审，第 4 版不能批
        long id4 = ok(call(qa, "/api/artworks", Map.of("pkItemId", pk, "fgItemId", fg, "changeNote", "第四版"))).get("id").asLong();
        upload(qa, id4);
        long draft = ok(call(admin, "/api/plm/formulas/" + fg + "/draft", Map.of("changeNote", "调整用量", "lines", List.of(
                Map.of("itemId", rm, "qtyPer", new BigDecimal("0.02"), "supplyMode", "BRAND"), Map.of("itemId", pk, "qtyPer", BigDecimal.ONE, "supplyMode", "BRAND"))))).get("id").asLong();
        ok(call(admin, "/api/plm/versions/" + draft + "/submit", Map.of()));
        long filing = ok(call(qa, "/api/plm/filings", Map.of("fgItemId", fg, "filingType", "GENERAL", "formulaVersionId", draft, "submittedDate", time.today().toString()))).get("id").asLong();
        ok(call(qa, "/api/plm/filings/" + filing + "/complete", Map.of("filingNo", "备案AW-V2", "filedDate", time.today().toString())));
        ok(call(qso, "/api/plm/versions/" + draft + "/approve", Map.of("password", PWD)));
        call(qso, "/api/artworks/" + id4 + "/approve", Map.of("checked", ALL, "password", PWD)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("配方变了")));
        assertThat(ok(mvc.perform(get("/api/dashboard").header("Authorization", "Bearer " + qa))).get("todos").toString()).contains("标签设计稿要重新评审").contains("物料AW-PK");
    }
}
