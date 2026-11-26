package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.oemsupervision.OemSupervisionService;
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
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 受托生产监督（检查要点委托生产版第 9、18 项）。审核过的监督会影响总览和自查核对，用单独的库。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-oem-supervision;MODE=MySQL;DB_CLOSE_DELAY=-1")
class OemSupervisionTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;
    private static final String BASE = "/api/oem-supervision";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TimeProvider time;
    @Autowired OemSupervisionService service;

    private String login(String u) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", u, "password", u.equals("admin") ? "Admin@123456" : PWD)))).andExpect(status().isOk())
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

    private long idOf(String t, String url, String code) throws Exception {
        for (JsonNode n : read(t, url)) {
            if (code.equals(n.get("code").asText())) {
                return n.get("id").asLong();
            }
        }
        throw new AssertionError(code);
    }

    private static Map<String, Object> record(long factory, Long mo, String method, LocalDate day, List<String> scopes) {
        Map<String, Object> m = new HashMap<>();
        m.put("factoryId", factory);
        m.put("moId", mo);
        m.put("method", method);
        m.put("supervisedOn", day.toString());
        m.put("scopes", scopes);
        m.put("participants", "品牌方 QA");
        m.put("content", "驻厂监督配制和灌装");
        return m;
    }

    private static Map<String, Object> finding(String severity, String text, String correction) {
        Map<String, Object> m = new HashMap<>();
        m.put("severity", severity);
        m.put("description", text);
        m.put("correction", correction);
        return m;
    }

    private static Map<String, Object> capa(LocalDate due) {
        return Map.of("actionType", "CORRECTIVE", "description", "受托方修订灌装 SOP 并培训", "owner", "qa1", "dueDate", due.toString());
    }

    private static JsonNode findingOf(JsonNode r, int seq) {
        for (JsonNode f : r.get("findings")) {
            if (f.get("seq").asInt() == seq) {
                return f;
            }
        }
        throw new AssertionError("问题 " + seq);
    }

    private JsonNode demo(String t, String method) throws Exception {
        for (JsonNode r : read(t, BASE).get("records")) {
            if (r.get("method").asText().equals(method)) {
                return r;
            }
        }
        throw new AssertionError(method);
    }

    private String todos(String t) throws Exception {
        return read(t, "/api/dashboard").get("todos").toString();
    }

    private Map<Integer, JsonNode> evidence(String t, long checkId) throws Exception {
        Map<Integer, JsonNode> byNo = new HashMap<>();
        read(t, "/api/qms/self-checks/" + checkId + "/evidence").forEach(e -> byNo.put(e.get("itemNo").asInt(), e));
        return byNo;
    }

    @Test
    void recordFindingsCapaSubmitReviewAndSelfCheckEvidence() throws Exception {
        LocalDate today = time.today();
        String admin = login("admin");
        String qa = login("qa1");
        String qso = login("qso1");
        String viewer = login("viewer1");
        long a3 = idOf(admin, "/api/partners", "OEM-A3");
        long own = idOf(admin, "/api/partners", "OWN-01");

        // 示例数据：驻厂监督已审核，批记录调阅待审核；禾川有在做的委托单，最近一次监督是昨天，不到期
        JsonNode demoOnSite = demo(qa, "ON_SITE");
        assertThat(demoOnSite.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(demoOnSite.get("conclusion").asText()).isEqualTo("MINOR");
        assertThat(demoOnSite.get("findings").get(0).get("handled").asBoolean()).isTrue();
        JsonNode demoReview = demo(qa, "RECORD_REVIEW");
        assertThat(demoReview.get("status").asText()).isEqualTo("SUBMITTED");
        JsonNode factory = read(qa, BASE).get("factories").get(0);
        assertThat(factory.get("factoryCode").asText()).isEqualTo("OEM-A3");
        assertThat(factory.get("openOrders").asInt()).isPositive();
        assertThat(factory.get("lastSupervised").asText()).isEqualTo(today.minusDays(1).toString());
        assertThat(factory.get("due").asBoolean()).isFalse();
        assertThat(todos(qso)).contains("受托生产监督待审核").contains(demoReview.get("supervisionNo").asText()).doesNotContain("受托厂监督到期");
        // 监督周期设成 0 天：最近一次监督是昨天，就算到期，进总览待办
        Object target = AopTestUtils.getTargetObject(service);
        ReflectionTestUtils.setField(target, "intervalDays", 0);
        try {
            assertThat(read(qa, BASE).get("factories").get(0).get("due").asBoolean()).isTrue();
            assertThat(todos(qa)).contains("受托厂监督到期").contains("/oem-supervision?tab=factories");
        } finally {
            ReflectionTestUtils.setField(target, "intervalDays", 90);
        }
        assertThat(todos(qa)).doesNotContain("受托厂监督到期");

        // 委托单页签：在做的委托单和它的监督
        long openMo = -1;
        for (JsonNode o : read(qa, BASE + "/orders")) {
            if (o.get("status").asText().equals("ISSUED")) {
                openMo = o.get("moId").asLong();
                assertThat(o.get("effectiveCount").asInt()).isZero();
            }
        }
        assertThat(openMo).isPositive();
        long ownMo = -1;
        for (JsonNode m : read(admin, "/api/mfg-orders")) {
            if (m.get("factoryId").asLong() == own) {
                ownMo = m.get("id").asLong();
            }
        }
        assertThat(ownMo).isPositive();

        // 登记规则
        call(viewer, BASE, record(a3, openMo, "ON_SITE", today, List.of("PROCESS"))).andExpect(status().isForbidden());
        call(qa, BASE, record(own, null, "ON_SITE", today, List.of("PROCESS"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("不是受托生产企业")));
        call(qa, BASE, record(a3, null, "ON_SITE", today.plusDays(1), List.of("PROCESS"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("不能晚于今天")));
        call(qa, BASE, record(a3, null, "ON_SITE", today, List.of())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("至少选一个监督的环节")));
        call(qa, BASE, record(a3, ownMo, "ON_SITE", today, List.of("PROCESS"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("不是委托")));

        JsonNode r = ok(call(qa, BASE, record(a3, openMo, "ON_SITE", today, List.of("TESTING", "PROCESS", "PROCESS"))));
        long id = r.get("id").asLong();
        String url = BASE + "/" + id;
        String no = r.get("supervisionNo").asText();
        assertThat(no).startsWith("JD-");
        assertThat(r.get("status").asText()).isEqualTo("DRAFT");
        assertThat(r.get("scopeLabels").asText()).isEqualTo("生产过程、检验");
        assertThat(r.get("moNo").asText()).isNotBlank();
        // 修改：改成定期现场检查，不关联委托单
        r = ok(mvc.perform(put(url).header("Authorization", "Bearer " + qa).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(record(a3, openMo, "INSPECTION", today, List.of("PROCESS", "TESTING", "RECORDS"))))));
        assertThat(r.get("methodLabel").asText()).isEqualTo("现场检查");

        // 问题：一般问题要当场纠正或转 CAPA，重大问题必须转 CAPA
        ok(call(qa, url + "/findings", finding("MINOR", "称量记录有一处涂改未签名", null)));
        r = ok(call(qa, url + "/findings", finding("MAJOR", "灌装工序未按规定做首件确认", "已当场要求补做")));
        assertThat(r.get("conclusion").asText()).isEqualTo("MAJOR");
        assertThat(r.get("unhandledCount").asInt()).isEqualTo(2);
        long minorId = findingOf(r, 1).get("id").asLong();
        long majorId = findingOf(r, 2).get("id").asLong();
        call(qa, url + "/submit", Map.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("问题 1 还没有写当场纠正的情况")))
                .andExpect(jsonPath("$.message", containsString("问题 2 是重大问题，要转 CAPA")));
        call(qa, url + "/findings/" + majorId + "/correction", Map.of("correction", "x")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("要转 CAPA")));
        r = ok(call(qa, url + "/findings/" + minorId + "/correction", Map.of("correction", "当场要求补签并说明原因")));
        assertThat(findingOf(r, 1).get("handled").asBoolean()).isTrue();
        call(qa, url + "/findings/" + majorId + "/capa", capa(today.minusDays(1))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("不能早于今天")));
        r = ok(call(qa, url + "/findings/" + majorId + "/capa", capa(today.plusDays(30))));
        String capaNo = findingOf(r, 2).get("capaNo").asText();
        assertThat(capaNo).startsWith("CA");
        call(qa, url + "/findings/" + majorId + "/capa", capa(today.plusDays(30))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("已经转过")));
        call(qa, url + "/findings/" + majorId + "/remove", Map.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("已经转了 CAPA")));
        assertThat(read(qa, "/api/qms/capas").toString()).contains(capaNo).contains("受托生产监督").contains(no + " 问题2");
        // 登记错的问题删掉，后面的序号不变
        r = ok(call(qa, url + "/findings", finding("MINOR", "登记错了", null)));
        r = ok(call(qa, url + "/findings/" + findingOf(r, 3).get("id").asLong() + "/remove", Map.of()));
        assertThat(r.get("findings").size()).isEqualTo(2);
        // 已审核的记录不能再登记纠正情况
        call(qa, BASE + "/" + demoOnSite.get("id").asLong() + "/findings/" + demoOnSite.get("findings").get(0).get("id").asLong() + "/correction",
                Map.of("correction", "x")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不能登记纠正情况")));

        // 附件
        mvc.perform(multipart("/api/attachments").file(new MockMultipartFile("file", "检查表.pdf", "application/pdf", "%PDF-1.4 sv".getBytes(StandardCharsets.UTF_8)))
                .param("ownerType", "OEM_SUPERVISION").param("ownerId", String.valueOf(id)).header("Authorization", "Bearer " + qa)).andExpect(status().isOk());

        // 提交后不能再改
        r = ok(call(qa, url + "/submit", Map.of()));
        assertThat(r.get("status").asText()).isEqualTo("SUBMITTED");
        assertThat(r.get("hasFile").asBoolean()).isTrue();
        assertThat(r.get("openCapaCount").asInt()).isEqualTo(1);
        call(qa, url + "/findings", finding("MINOR", "x", "y")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不能登记问题")));

        // 审核：只给质量安全负责人，要密码；退回后补充再提交
        call(qa, url + "/confirm", Map.of("password", PWD)).andExpect(status().isForbidden());
        call(qso, url + "/confirm", Map.of("password", "wrong")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("签批密码不正确")));
        r = ok(call(qso, url + "/reject", Map.of("note", "补充首件确认的整改要求")));
        assertThat(r.get("status").asText()).isEqualTo("DRAFT");
        assertThat(r.get("rejectNote").asText()).contains("首件确认");
        ok(call(qa, url + "/submit", Map.of()));
        r = ok(call(qso, url + "/confirm", Map.of("note", "整改完成后复查", "password", PWD)));
        assertThat(r.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(r.get("reviewedBy").asText()).isEqualTo("qso1");
        call(qa, url + "/cancel", Map.of("note", "x")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不能作废")));

        // 自己登记、自己提交的不能自己审核；作废后问题保留
        JsonNode own2 = ok(call(qso, BASE, record(a3, null, "REMOTE", today, List.of("ENVIRONMENT"))));
        String own2Url = BASE + "/" + own2.get("id").asLong();
        // 别的监督记录的问题，不能借这条记录的路径改
        call(qso, own2Url + "/findings/" + minorId + "/correction", Map.of("correction", "x")).andExpect(status().isNotFound());
        ok(call(qso, own2Url + "/submit", Map.of()));
        call(qso, own2Url + "/confirm", Map.of("password", PWD)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("不能是监督记录的登记人或提交人")));
        assertThat(ok(call(qso, own2Url + "/cancel", Map.of("note", "登记重复"))).get("status").asText()).isEqualTo("CANCELLED");

        // 自查核对：示例的批记录调阅还没审核，第 9 项提示；第 10 项提示重大问题整改没验证、还有在做的委托单
        long checkId = ok(call(qa, "/api/qms/self-checks", Map.of("periodFrom", today.minusDays(30).toString(), "periodTo", today.toString())))
                .get("check").get("id").asLong();
        Map<Integer, JsonNode> ev = evidence(qa, checkId);
        assertThat(ev.get(9).get("level").asText()).isEqualTo("WARN");
        assertThat(ev.get(9).get("summary").asText()).contains("1 份监督记录还没有质量安全负责人审核");
        assertThat(ev.get(9).get("details").toString()).contains(no).contains("CAPA " + capaNo);
        assertThat(ev.get(10).get("level").asText()).isEqualTo("WARN");
        assertThat(ev.get(10).get("details").toString()).contains("请评估是否需要停止委托").contains(no + " 问题 2");
        assertThat(ev.get(18).get("summary").asText()).contains("调阅或检查受托方生产质量记录 2 次");
        assertThat(ev.get(18).get("details").toString()).contains(demoReview.get("supervisionNo").asText());

        ok(call(qso, BASE + "/" + demoReview.get("id").asLong() + "/confirm", Map.of("password", PWD)));
        ev = evidence(qa, checkId);
        assertThat(ev.get(9).get("summary").asText()).contains("1 家受托厂都有监督记录，都已审核").contains("发现问题 3 个（重大 1 个）");
        assertThat(todos(qso)).doesNotContain("受托生产监督待审核");

        // 导出
        mvc.perform(get("/api/reports/oem-supervision").header("Authorization", "Bearer " + qa)).andExpect(status().isOk());
    }
}
