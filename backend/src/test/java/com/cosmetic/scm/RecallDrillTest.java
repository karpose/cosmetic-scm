package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.recall.RecallDrillService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 召回演练（检查要点委托生产版第 24 项）。审核过的演练会影响总览和自查核对，用单独的库。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-recall-drill;MODE=MySQL;DB_CLOSE_DELAY=-1")
class RecallDrillTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;
    private static final String BASE = "/api/recall-drills";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TimeProvider time;
    @Autowired RecallDrillService service;

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

    private String todos(String t) throws Exception {
        return read(t, "/api/dashboard").get("todos").toString();
    }

    private static Map<String, Object> start(String batchNo, int hours) {
        return Map.of("batchNo", batchNo, "scenario", "假设有消费者投诉异味", "targetHours", hours);
    }

    private static Map<String, Object> rec(String result, Object qty, String at, String note) {
        Map<String, Object> m = new HashMap<>();
        m.put("result", result);
        m.put("confirmedQty", qty);
        m.put("contactedAt", at);
        m.put("note", note);
        return m;
    }

    private static JsonNode line(JsonNode d, String target, int nth) {
        int i = 0;
        for (JsonNode l : d.get("lines")) {
            if (l.get("target").asText().equals(target) && i++ == nth) {
                return l;
            }
        }
        throw new AssertionError(target + " " + nth);
    }

    private Map<Integer, JsonNode> evidence(String t, long checkId) throws Exception {
        Map<Integer, JsonNode> byNo = new HashMap<>();
        read(t, "/api/qms/self-checks/" + checkId + "/evidence").forEach(e -> byNo.put(e.get("itemNo").asInt(), e));
        return byNo;
    }

    @Test
    void startRecordCapaSubmitReviewTodosAndSelfCheck() throws Exception {
        String qa = login("qa1");
        String qso = login("qso1");
        String sales = login("sales1");
        String viewer = login("viewer1");

        // 示例数据：第一批精华液演练过一次，都联系上，已审核，不到期
        JsonNode ov = read(qa, BASE);
        assertThat(ov.get("drills").size()).isEqualTo(1);
        JsonNode demo = ov.get("drills").get(0);
        assertThat(demo.get("status").asText()).isEqualTo("REVIEWED");
        assertThat(demo.get("met").asBoolean()).isTrue();
        assertThat(demo.get("locatedPct").decimalValue()).isEqualByComparingTo("100");
        assertThat(demo.get("balance").asText()).contains("完工入库 +9950").contains("销售出库 -");
        assertThat(ov.get("due").asBoolean()).isFalse();
        assertThat(ov.get("lastReviewed").asText()).isEqualTo(time.today().toString());
        String batchNo = demo.get("batchNo").asText();
        assertThat(todos(qa)).doesNotContain("召回演练");

        // 只针对已发货的成品批次
        call(viewer, BASE, start(batchNo, 4)).andExpect(status().isForbidden());
        String material = null;
        String unshipped = null;
        for (JsonNode b : read(qa, "/api/batches")) {
            if (!"FG".equals(b.get("itemType").asText()) && material == null) {
                material = b.get("batchNo").asText();
            }
            if ("FG".equals(b.get("itemType").asText()) && "PENDING_RELEASE".equals(b.get("status").asText())) {
                unshipped = b.get("batchNo").asText();
            }
        }
        call(qa, BASE, start(material, 4)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不是成品")));
        call(qa, BASE, start(unshipped, 4)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("没有在外的数量")));
        call(qa, BASE, start(batchNo, 0)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("至少 1 小时")));

        // 预览和发起：客户（在外数量）、受托厂、仓库
        JsonNode plan = read(qa, BASE + "/preview?batchNo=" + batchNo);
        assertThat(plan.get("lines").toString()).contains("CUSTOMER").contains("FACTORY").contains("WAREHOUSE").contains("禾川");
        JsonNode d = ok(call(qa, BASE, start(batchNo, 4)));
        long id = d.get("id").asLong();
        String url = BASE + "/" + id;
        String no = d.get("drillNo").asText();
        assertThat(no).startsWith("RD");
        assertThat(d.get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(d.get("lines").size()).isEqualTo(plan.get("lines").size());
        // 只是演练：批次没有冻结
        assertThat(read(qa, "/api/trace/" + batchNo).get("batch").get("status").asText()).isEqualTo("RELEASED");

        JsonNode c1 = line(d, "CUSTOMER", 0);
        JsonNode fac = line(d, "FACTORY", 0);
        JsonNode wh = line(d, "WAREHOUSE", 0);
        String now = time.now().withNano(0).toString();
        call(viewer, url + "/lines/" + c1.get("id").asLong() + "/record", rec("REACHED", null, null, null)).andExpect(status().isForbidden());
        call(qa, url + "/lines/" + c1.get("id").asLong() + "/record", rec("REACHED", null, time.now().plusHours(1).toString(), null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不能晚于现在")));
        call(qa, url + "/lines/" + c1.get("id").asLong() + "/record", rec("REACHED", null, time.now().minusDays(1).toString(), null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不能早于演练发起时间")));
        call(qa, url + "/lines/" + c1.get("id").asLong() + "/record", rec("UNREACHABLE", null, null, " "))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("试过的方式")));
        call(qa, url + "/lines/" + wh.get("id").asLong() + "/record", rec("REACHED", null, null, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("实盘数量")));
        // 别的演练的对象，不能借这条演练的路径登记
        call(qa, BASE + "/" + demo.get("id").asLong() + "/lines/" + c1.get("id").asLong() + "/record", rec("REACHED", null, null, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不能登记联系结果")));

        // 销售登记客户：第一家联系不上；受托厂联系上；仓库实盘少了 1
        d = ok(call(sales, url + "/lines/" + c1.get("id").asLong() + "/record", rec("UNREACHABLE", null, now, "电话两次未接、微信未回")));
        assertThat(line(d, "CUSTOMER", 0).get("recordedBy").asText()).isEqualTo("sales1");
        call(qa, url + "/submit", Map.of("summary", "x")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("还没有登记联系结果")));
        ok(call(qa, url + "/lines/" + fac.get("id").asLong() + "/record", rec("REACHED", 5, null, "受托厂确认可以停产")));
        BigDecimal book = wh.get("expectedQty").decimalValue();
        d = ok(call(qa, url + "/lines/" + wh.get("id").asLong() + "/record", rec("REACHED", book.subtract(BigDecimal.ONE), null, null)));
        // 受托厂不记数量
        assertThat(line(d, "FACTORY", 0).get("confirmedQty").isNull()).isTrue();
        for (JsonNode l : d.get("lines")) {
            if (l.get("result").isNull()) {
                d = ok(call(qa, url + "/lines/" + l.get("id").asLong() + "/record", rec("REACHED", l.get("target").asText().equals("WAREHOUSE")
                        ? l.get("expectedQty").decimalValue() : null, null, null)));
            }
        }
        assertThat(d.get("recordedCount").asInt()).isEqualTo(d.get("lines").size());
        assertThat(d.get("met").asBoolean()).isFalse();
        assertThat(d.get("gaps").toString()).contains("联系不上").contains("实盘");
        assertThat(d.get("locatedPct").decimalValue()).isLessThan(new BigDecimal("100"));

        // 没达标：先转 CAPA 才能提交
        call(qa, url + "/submit", Map.of("summary", "第一家客户联系不上")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("请先转 CAPA")));
        Map<String, Object> capa = Map.of("actionType", "CORRECTIVE", "description", "更新客户紧急联系人名单", "owner", "sales1",
                "dueDate", time.today().plusDays(30).toString());
        d = ok(call(qa, url + "/capa", capa));
        String capaNo = d.get("capaNo").asText();
        assertThat(capaNo).startsWith("CA");
        call(qa, url + "/capa", capa).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("已经转过")));
        assertThat(read(qa, "/api/qms/capas").toString()).contains(capaNo).contains("召回演练");
        d = ok(call(qa, url + "/submit", Map.of("summary", "第一家客户联系不上，仓库差 1")));
        assertThat(d.get("status").asText()).isEqualTo("SUBMITTED");
        call(qa, url + "/lines/" + c1.get("id").asLong() + "/record", rec("REACHED", null, null, null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("不能登记联系结果")));
        assertThat(todos(qso)).contains("召回演练待审核").contains(no).contains("/quality-events?tab=drills");

        // 审核：只给质量安全负责人，要密码；退回后补登记再提交
        call(qa, url + "/review", Map.of("password", PWD)).andExpect(status().isForbidden());
        call(qso, url + "/review", Map.of("password", "wrong")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("签批密码不正确")));
        d = ok(call(qso, url + "/reject", Map.of("note", "仓库差异要写明原因")));
        assertThat(d.get("status").asText()).isEqualTo("IN_PROGRESS");
        ok(call(qa, url + "/lines/" + wh.get("id").asLong() + "/record", rec("REACHED", book.subtract(BigDecimal.ONE), null, "差 1 瓶是留样，已补登记")));
        ok(call(qa, url + "/submit", Map.of("summary", "第一家客户联系不上，仓库差 1 是留样")));
        d = ok(call(qso, url + "/review", Map.of("note", "跟进 CAPA", "password", PWD)));
        assertThat(d.get("status").asText()).isEqualTo("REVIEWED");
        assertThat(d.get("reviewedBy").asText()).isEqualTo("qso1");
        assertThat(todos(qso)).doesNotContain("召回演练待审核");
        call(qa, url + "/cancel", Map.of("note", "x")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不能作废")));

        // 自己发起、自己提交的不能自己审核；作废
        JsonNode own = ok(call(qso, BASE, start(batchNo, 8)));
        String ownUrl = BASE + "/" + own.get("id").asLong();
        for (JsonNode l : own.get("lines")) {
            ok(call(qso, ownUrl + "/lines/" + l.get("id").asLong() + "/record", rec("REACHED", l.get("target").asText().equals("WAREHOUSE")
                    ? l.get("expectedQty").decimalValue() : null, null, null)));
        }
        JsonNode sub = ok(call(qso, ownUrl + "/submit", Map.of("summary", "都联系上了")));
        assertThat(sub.get("met").asBoolean()).isTrue();
        call(qso, ownUrl + "/review", Map.of("password", PWD)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("不能是演练的发起人或提交人")));
        assertThat(ok(call(qso, ownUrl + "/cancel", Map.of("note", "演示用"))).get("status").asText()).isEqualTo("CANCELLED");

        // 到期提醒：周期设成 -1 个月，今天审核过的也算超期
        Object target = AopTestUtils.getTargetObject(service);
        ReflectionTestUtils.setField(target, "intervalMonths", -1);
        try {
            assertThat(read(qa, BASE).get("due").asBoolean()).isTrue();
            assertThat(todos(qa)).contains("召回演练到期").contains(no);
        } finally {
            ReflectionTestUtils.setField(target, "intervalMonths", 12);
        }
        assertThat(todos(qa)).doesNotContain("召回演练到期");

        // 自查第 24 项：最近一次审核过的演练没达标，CAPA 还没验证
        long checkId = ok(call(qa, "/api/qms/self-checks", Map.of("periodFrom", time.today().minusDays(30).toString(), "periodTo", time.today().toString())))
                .get("check").get("id").asLong();
        JsonNode ev = evidence(qa, checkId).get(24);
        assertThat(ev.get("level").asText()).isEqualTo("WARN");
        assertThat(ev.get("summary").asText()).contains("审核过的召回演练 2 次");
        assertThat(ev.get("details").toString()).contains("最近一次召回演练 " + no + " 没达标").contains("CAPA " + capaNo).contains(demo.get("drillNo").asText())
                .doesNotContain(own.get("drillNo").asText());
        assertThat(ev.get("summary").asText()).doesNotContain("请人工核对");

        // 导出
        mvc.perform(get("/api/reports/recall-drills").header("Authorization", "Bearer " + qa)).andExpect(status().isOk());
    }
}
