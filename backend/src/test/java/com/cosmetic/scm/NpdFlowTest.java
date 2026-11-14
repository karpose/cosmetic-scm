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
import java.time.LocalDate;
import java.util.HashMap;
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

/**
 * 新品立项、试制和上市检查。
 * 用单独的库：这里建了成品、配方、标准，留在共用库里会影响上线检查和主线计数的用例。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-npd;MODE=MySQL;DB_CLOSE_DELAY=-1")
class NpdFlowTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TimeProvider time;

    private String login(String u) throws Exception {
        return login(u, PWD);
    }

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

    private JsonNode ok(ResultActions r) throws Exception {
        return body(r.andExpect(status().isOk()));
    }

    private JsonNode read(String t, String url) throws Exception {
        return ok(mvc.perform(get(url).header("Authorization", "Bearer " + t)));
    }

    private Map<String, Object> project(String name, LocalDate target) {
        return Map.of("name", name, "category", "面霜", "filingType", "GENERAL", "claims", "保湿", "targetLaunch", target.toString());
    }

    private Map<String, Object> trial(String kind, Long versionId, String result) {
        Map<String, Object> m = new HashMap<>(Map.of("kind", kind, "trialDate", time.today().toString(), "result", result, "summary", "测试", "batchSize", "5 kg"));
        if (versionId != null) {
            m.put("formulaVersionId", versionId);
        }
        return m;
    }

    private String checks(JsonNode d) {
        StringBuilder sb = new StringBuilder();
        d.get("checks").forEach(c -> sb.append(c.get("title").asText()).append('=').append(c.get("ok").asBoolean()).append(';'));
        return sb.toString();
    }

    /** 建一个成品，配方生效，备案编号随主数据生成。 */
    private long[] product(String admin, String code) throws Exception {
        long m = ok(call(admin, "/api/items", Map.of("code", code + "-RM", "name", "原料" + code, "itemType", "RM", "unit", "kg", "banned", false, "active", true, "shelfMonths", 24))).get("id").asLong();
        long f = ok(call(admin, "/api/items", Map.of("code", code, "name", "成品" + code, "itemType", "FG", "unit", "瓶", "banned", false, "active", true, "shelfMonths", 24, "netG", 50, "filingNo", "备案" + code))).get("id").asLong();
        long pk = ok(call(admin, "/api/items", Map.of("code", code + "-PK", "name", "包材" + code, "itemType", "PK", "unit", "个", "banned", false, "active", true, "shelfMonths", 36))).get("id").asLong();
        mvc.perform(put("/api/boms/" + f).header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("lines", List.of(Map.of("itemId", m, "qtyPer", new BigDecimal("0.01"), "supplyMode", "BRAND"),
                        Map.of("itemId", pk, "qtyPer", BigDecimal.ONE, "supplyMode", "BRAND")))))).andExpect(status().isOk());
        return new long[] {f, m, pk};
    }

    private long effectiveVersion(String t, long fg) throws Exception {
        for (JsonNode v : read(t, "/api/plm/formulas")) {
            if (v.get("fgItemId").asLong() == fg && "EFFECTIVE".equals(v.get("status").asText())) {
                return v.get("id").asLong();
            }
        }
        throw new AssertionError("no effective version");
    }

    @Test
    void projectNeedsApprovalAndLaunchNeedsEveryCheck() throws Exception {
        String admin = login("admin", "Admin@123456");
        String qa = login("qa1");
        String qso = login("qso1");
        LocalDate today = time.today();

        // 立项
        call(login("purchase1"), "/api/npd/projects", project("x", today.plusMonths(3))).andExpect(status().isForbidden());
        call(qa, "/api/npd/projects", project("x", today.minusDays(1))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不能早于今天")));
        JsonNode d = ok(call(qa, "/api/npd/projects", project("测试面霜", today.plusMonths(3))));
        long id = d.get("project").get("id").asLong();
        String base = "/api/npd/projects/" + id;
        assertThat(d.get("project").get("status").asText()).isEqualTo("PROPOSED");
        assertThat(read(admin, "/api/dashboard").get("todos").toString()).contains("新品立项待批准");
        // 没批准不能做事
        call(qa, base + "/trials", trial("LAB", null, "PASS")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("待立项审批")));
        call(qa, base + "/approve", Map.of()).andExpect(status().isForbidden());
        // 管理员不能批自己的
        long own = ok(call(admin, "/api/npd/projects", project("自己的", today.plusMonths(3)))).get("project").get("id").asLong();
        call(admin, "/api/npd/projects/" + own + "/approve", Map.of()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("申请人")));
        call(admin, "/api/npd/projects/" + own + "/reject", Map.of("note", "不做了")).andExpect(status().isOk());
        d = ok(call(admin, base + "/approve", Map.of()));
        assertThat(d.get("project").get("status").asText()).isEqualTo("ACTIVE");

        // 没关联成品：只有一项，没通过
        assertThat(checks(d)).isEqualTo("关联成品=false;");
        call(qa, base + "/submit", Map.of()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("关联成品")));
        // 中试要选版本，选版本要先关联成品
        call(qa, base + "/trials", trial("PILOT", null, "PASS")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("配方版本")));
        ok(call(login("prod1"), base + "/trials", trial("LAB", null, "ADJUST")));
        Map<String, Object> future = trial("LAB", null, "PASS");
        future.put("trialDate", today.plusDays(1).toString());
        call(qa, base + "/trials", future).andExpect(status().isBadRequest());

        // 关联成品：不是成品不行，别的项目占着也不行
        long[] pm = product(admin, "NPD-FG1");
        long fg = pm[0];
        call(qa, base + "/link", Map.of("fgItemId", pm[1])).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不是成品")));
        d = ok(call(qa, base + "/link", Map.of("fgItemId", fg)));
        long other = ok(call(qa, "/api/npd/projects", project("另一个", today.plusMonths(3)))).get("project").get("id").asLong();
        ok(call(admin, "/api/npd/projects/" + other + "/approve", Map.of()));
        call(qa, "/api/npd/projects/" + other + "/link", Map.of("fgItemId", fg)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("已关联项目")));
        // 别的成品的配方版本不能用
        long[] pm2 = product(admin, "NPD-FG2");
        call(qa, base + "/trials", trial("PILOT", effectiveVersion(qa, pm2[0]), "PASS")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不属于")));

        // 有配方、有备案，其余都没有
        assertThat(checks(d)).isEqualTo("关联成品=true;生效配方=true;备案或注册=true;安全评估和标签=false;包材设计稿=false;稳定性依据=false;质量标准=false;中试通过=false;");
        long v1 = effectiveVersion(qa, fg);

        // 补齐：合规资料
        for (String type : List.of("SAFETY", "LABEL")) {
            long doc = ok(call(qa, "/api/compliance/docs", Map.of("fgItemId", fg, "docType", type, "title", type, "evaluator", "测试", "docDate", today.toString()))).get("id").asLong();
            ok(call(qso, "/api/compliance/docs/" + doc + "/approve", Map.of("password", PWD)));
        }
        // 稳定性：支持 24 个月，等于标的保质期
        JsonNode st = ok(call(qa, "/api/stability/studies", Map.of("fgItemId", fg, "studyType", "LONG_TERM", "sampleRef", "中试样品", "condition", "测试", "startDate", today.toString(),
                "timepoints", List.of(Map.of("months", 0, "days", 0)), "tests", List.of(Map.of("name", "外观")))));
        String sid = "/api/stability/studies/" + st.get("study").get("id").asLong();
        ok(call(qa, sid + "/pulls/" + st.get("pulls").get(0).get("id").asLong() + "/record", Map.of("doneDate", today.toString(),
                "results", List.of(Map.of("testId", st.get("tests").get(0).get("id").asLong(), "text", "正常", "passed", true)))));
        ok(call(qa, sid + "/conclude", Map.of("conclusion", "测试", "supportedMonths", 24)));
        ok(call(qso, sid + "/approve", Map.of("password", PWD)));
        // 质量标准
        long spec = ok(call(qa, "/api/quality/specs", Map.of("itemId", fg, "changeNote", "首版", "lines", List.of(Map.of("name", "外观"))))).get("id").asLong();
        ok(call(qso, "/api/quality/specs/" + spec + "/approve", Map.of("password", PWD)));
        // 包材设计稿：提交了还不算，批准后才算
        long art = ok(call(qa, "/api/artworks", Map.of("pkItemId", pm[2], "fgItemId", fg, "changeNote", "首版"))).get("id").asLong();
        assertThat(read(qa, base).get("checks").toString()).contains("配方里的包材还没有已批准的设计稿");
        mvc.perform(multipart("/api/attachments").file(new MockMultipartFile("file", "art.pdf", "application/pdf", "%PDF-1.4 art".getBytes(StandardCharsets.UTF_8)))
                .param("ownerType", "LABEL_ARTWORK").param("ownerId", String.valueOf(art)).header("Authorization", "Bearer " + qa)).andExpect(status().isOk());
        ok(call(qso, "/api/artworks/" + art + "/approve", Map.of("checked", List.of("INGREDIENTS", "FILING", "NET", "USAGE", "WARNING", "COMPANY", "BATCH"), "password", PWD)));
        assertThat(read(qa, base).get("checks").toString()).contains("包材NPD-FG1 第 1 版 已批准");
        // 中试不通过不算
        ok(call(qa, base + "/trials", trial("PILOT", v1, "FAIL")));
        d = read(qa, base);
        assertThat(checks(d)).endsWith("中试通过=false;");
        assertThat(d.get("ready").asBoolean()).isFalse();
        d = ok(call(qa, base + "/trials", trial("PILOT", v1, "PASS")));
        assertThat(d.get("ready").asBoolean()).isTrue();
        assertThat(d.get("trials").get(0).get("versionNo").asInt()).isEqualTo(1);

        // 提交、批准：只给 QSO，不能是提交人
        ok(call(qa, base + "/submit", Map.of()));
        assertThat(read(qso, "/api/dashboard").get("todos").toString()).contains("新品上市待批准");
        call(qa, base + "/launch", Map.of("password", PWD)).andExpect(status().isForbidden());
        call(qso, base + "/launch", Map.of("password", "wrong")).andExpect(status().isBadRequest());
        call(qso, base + "/launch-reject", Map.of("note", "再看看")).andExpect(status().isOk());
        ok(call(qso, base + "/submit", Map.of()));
        call(qso, base + "/launch", Map.of("password", PWD)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("提交人")));
        ok(call(qso, base + "/launch-reject", Map.of("note", "换人提交")));
        ok(call(qa, base + "/submit", Map.of()));

        // 提交后标签被作废：批准时重新检查，拦住
        String docs = read(qa, "/api/compliance/docs").toString();
        long label = 0;
        for (JsonNode x : read(qa, "/api/compliance/docs")) {
            if (x.get("fgItemId").asLong() == fg && "LABEL".equals(x.get("docType").asText())) {
                label = x.get("id").asLong();
            }
        }
        assertThat(docs).contains("LABEL");
        ok(call(qso, "/api/compliance/docs/" + label + "/void", Map.of("note", "标签改版")));
        call(qso, base + "/launch", Map.of("password", PWD)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("安全评估和标签")));
        long label2 = ok(call(qa, "/api/compliance/docs", Map.of("fgItemId", fg, "docType", "LABEL", "title", "新标签", "evaluator", "测试", "docDate", today.toString()))).get("id").asLong();
        ok(call(qso, "/api/compliance/docs/" + label2 + "/approve", Map.of("password", PWD)));
        d = ok(call(qso, base + "/launch", Map.of("password", PWD)));
        assertThat(d.get("project").get("status").asText()).isEqualTo("LAUNCHED");
        assertThat(d.get("project").get("launchedBy").asText()).isEqualTo("qso1");
        // 上市后不能再改
        call(qa, base + "/trials", trial("LAB", null, "PASS")).andExpect(status().isBadRequest());
        call(admin, base + "/cancel", Map.of("note", "x")).andExpect(status().isBadRequest());
        // 已上市的成品也不能再被别的项目关联
        call(qa, "/api/npd/projects/" + other + "/link", Map.of("fgItemId", fg)).andExpect(status().isBadRequest());
    }

    @Test
    void cancelledProjectsFreeTheProduct() throws Exception {
        String admin = login("admin", "Admin@123456");
        String qa = login("qa1");
        LocalDate today = time.today();
        long id = ok(call(qa, "/api/npd/projects", project("会取消的", today))).get("project").get("id").asLong();
        ok(call(admin, "/api/npd/projects/" + id + "/approve", Map.of()));
        long fg = product(admin, "NPD-FG3")[0];
        ok(call(qa, "/api/npd/projects/" + id + "/link", Map.of("fgItemId", fg)));
        call(qa, "/api/npd/projects/" + id + "/cancel", Map.of("note", "x")).andExpect(status().isForbidden());
        ok(call(admin, "/api/npd/projects/" + id + "/cancel", Map.of("note", "方向调整")));
        long next = ok(call(qa, "/api/npd/projects", project("接手的", today.plusDays(1)))).get("project").get("id").asLong();
        ok(call(admin, "/api/npd/projects/" + next + "/approve", Map.of()));
        ok(call(qa, "/api/npd/projects/" + next + "/link", Map.of("fgItemId", fg)));
        assertThat(read(qa, "/api/npd/projects").toString()).contains("CANCELLED").contains("方向调整");
    }
}
