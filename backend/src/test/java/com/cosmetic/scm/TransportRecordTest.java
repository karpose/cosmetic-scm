package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
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

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.qms.GmpEvidenceService;
import com.cosmetic.scm.transport.TransportService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 产品运输条件和运输记录（检查要点委托生产版序号 20，GMP 第五十九条）。会生成偏差、影响总览和自查核对，用单独的库。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-transport;MODE=MySQL;DB_CLOSE_DELAY=-1")
class TransportRecordTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;
    private static final String BASE = "/api/transport";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TimeProvider time;
    @Autowired TransportService transport;
    @Autowired GmpEvidenceService evidence;

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

    private long id(String t, String url, String code) throws Exception {
        for (JsonNode n : read(t, url)) {
            if (code.equals(n.get("code").asText())) {
                return n.get("id").asLong();
            }
        }
        throw new AssertionError(code);
    }

    private void grace(int days) {
        ReflectionTestUtils.setField((Object) AopTestUtils.getTargetObject(transport), "graceDays", days);
        ReflectionTestUtils.setField((Object) AopTestUtils.getTargetObject(evidence), "transportGraceDays", days);
    }

    @AfterEach
    void resetGrace() {
        grace(3);
    }

    private Map<String, Object> rec(String direction, List<Long> docIds, String mode, String carrier, LocalDateTime shipped, Boolean monitored, String low, String high) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("direction", direction);
        m.put("docIds", docIds);
        m.put("mode", mode);
        m.put("carrier", carrier);
        m.put("shippedAt", shipped.toString());
        m.put("tempMonitored", monitored);
        m.put("tempLow", low);
        m.put("tempHigh", high);
        return m;
    }

    private JsonNode candidate(JsonNode list, String docNo) {
        for (JsonNode c : list) {
            if (c.get("docNo").asText().equals(docNo)) {
                return c;
            }
        }
        return null;
    }

    @Test
    void transportConditionsRecordsDeviationsTodosAndSelfCheck() throws Exception {
        String qa = login("qa1");
        String qso = login("qso1");
        String sales = login("sales1");
        String wh = login("purchase1"); // 有仓库角色
        String prod = login("prod1");
        String viewer = login("viewer1");
        LocalDate today = time.today();
        LocalDateTime now = time.now().truncatedTo(ChronoUnit.MINUTES);
        LocalDateTime shipped = now.minusHours(1).toLocalDate().isBefore(today) ? today.atStartOfDay() : now.minusHours(1);

        // 示例：精华液设了 5–30℃、要记录途中温度；第一批到仓和京东那单的运输都登记了，在范围内
        JsonNode reqs = read(viewer, BASE + "/requirements");
        assertThat(reqs.toString()).contains("烟酰胺精华液 30ml").contains("5–30℃，记录途中温度；避免阳光直射");
        JsonNode demo = read(viewer, BASE + "/records").get("rows");
        assertThat(demo).hasSize(2);
        demo.forEach(r -> assertThat(r.get("conforming").asBoolean()).isTrue());
        assertThat(demo.toString()).contains("京东自营入仓（示例）").contains("禾川生物（示例）").contains("顺丰速运（示例）");

        // 运输条件：只给质量部门设；只给成品；要求记录温度就要有范围；一个成品一条
        long cream = id(qa, "/api/items", "FG-CRM-002");
        long nia = id(qa, "/api/items", "RM-NIA-001");
        Map<String, Object> creamReq = new HashMap<>(Map.of("itemId", cream, "tempMin", "2", "tempMax", "25", "monitorRequired", false,
                "handlingNote", "防冻", "effectiveFrom", today.toString()));
        call(sales, BASE + "/requirements", creamReq).andExpect(status().isForbidden());
        call(qa, BASE + "/requirements", Map.of("itemId", nia, "tempMax", "30", "monitorRequired", false, "effectiveFrom", today.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不是成品")));
        call(qa, BASE + "/requirements", Map.of("itemId", cream, "monitorRequired", true, "handlingNote", "防压", "effectiveFrom", today.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("请设定温度范围")));
        JsonNode cr = ok(call(qa, BASE + "/requirements", creamReq));
        assertThat(cr.get("text").asText()).isEqualTo("2–25℃；防冻");
        call(qso, BASE + "/requirements", creamReq).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("已经设了运输条件")));
        Map<String, Object> creamEdit = new HashMap<>(creamReq);
        creamEdit.put("tempMax", "28");
        assertThat(ok(mvc.perform(put(BASE + "/requirements/" + cr.get("id").asLong()).header("Authorization", "Bearer " + qso)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(creamEdit)))).get("text").asText()).isEqualTo("2–28℃；防冻");
        assertThat(read(login("admin"), "/api/audit?q=修改运输条件").toString()).contains("温度上限");

        // 待登记：天猫那单、后两批完工的精华液；都在登记期限内，不进总览
        JsonNode missing = read(viewer, BASE + "/missing");
        assertThat(missing.get("graceDays").asInt()).isEqualTo(3);
        assertThat(missing.get("rows").toString()).contains("还没有运输记录").contains("还没有到仓运输记录");
        missing.get("rows").forEach(r -> assertThat(r.get("overdue").asBoolean()).isFalse());
        assertThat(todos(qa)).doesNotContain("运输记录未登记");
        grace(0);
        assertThat(todos(qa)).contains("运输记录未登记").contains("销售发运 华东主仓");
        grace(3);

        // 销售发运：天猫那单在可选列表里，要登记运输条件
        long main = id(qa, "/api/warehouses", "WH-MAIN");
        JsonNode outs = read(sales, BASE + "/candidates?direction=OUTBOUND&date=" + today + "&warehouseId=" + main);
        assertThat(outs.toString()).doesNotContain("京东自营入仓（示例）");
        JsonNode tm = null;
        for (JsonNode c : outs) {
            if (c.get("partyName").asText().equals("天猫旗舰店（示例）")) {
                tm = c;
            }
        }
        assertThat(tm).isNotNull();
        assertThat(tm.get("required").asBoolean()).isTrue();
        assertThat(tm.get("reqText").asText()).startsWith("5–30℃，记录途中温度");
        long tmId = tm.get("docId").asLong();
        String tmNo = tm.get("docNo").asText();

        // 承运单位必填（客户自提除外）；勾了记录温度要填最低最高；发运时间不能早于出库日期、不能晚于现在
        call(sales, BASE + "/records", rec("OUTBOUND", List.of(tmId), "EXPRESS", " ", shipped, true, "18", "24"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("承运单位")));
        call(sales, BASE + "/records", rec("OUTBOUND", List.of(tmId), "EXPRESS", "顺丰", shipped, true, "18", null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("最低和最高温度")));
        call(sales, BASE + "/records", rec("OUTBOUND", List.of(tmId), "EXPRESS", "顺丰", today.minusDays(1).atTime(10, 0), true, "18", "24"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不能早于出库日期")));
        call(sales, BASE + "/records", rec("OUTBOUND", List.of(tmId), "EXPRESS", "顺丰", now.plusHours(2), true, "18", "24"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不能晚于现在")));
        call(viewer, BASE + "/records", rec("OUTBOUND", List.of(tmId), "EXPRESS", "顺丰", shipped, true, "18", "24")).andExpect(status().isForbidden());

        // 途中最高 32℃，超出上限 30℃：不符合，生成一份运输偏差
        JsonNode hot = ok(call(sales, BASE + "/records", rec("OUTBOUND", List.of(tmId), "EXPRESS", "中通快递", shipped, true, "21", "32")));
        assertThat(hot.get("conforming").asBoolean()).isFalse();
        assertThat(hot.get("problem").asText()).isEqualTo("最高温度 32℃ 高于上限 30℃");
        String dv = hot.get("deviationNo").asText();
        assertThat(dv).startsWith("DV");
        assertThat(hot.get("recordNo").asText()).startsWith("YS");
        JsonNode devs = read(qa, "/api/qms/deviations");
        JsonNode dev = null;
        for (JsonNode d : devs) {
            if (d.get("deviationNo").asText().equals(dv)) {
                dev = d;
            }
        }
        assertThat(dev).isNotNull();
        assertThat(dev.get("areaLabel").asText()).isEqualTo("运输");
        assertThat(dev.get("levelLabel").asText()).isNotBlank();
        assertThat(dev.get("description").asText()).contains(tmNo).contains("最高温度 32℃");
        assertThat(dev.get("batchNo").isNull()).isFalse(); // 只出了一批，挂在批次上
        assertThat(todos(qso)).contains("运输条件不符合").contains(hot.get("recordNo").asText());

        // 一张单据只能在一条有效记录里
        call(sales, BASE + "/records", rec("OUTBOUND", List.of(tmId), "EXPRESS", "顺丰", shipped, true, "18", "24"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("已在运输记录 " + hot.get("recordNo").asText())));
        assertThat(candidate(read(sales, BASE + "/candidates?direction=OUTBOUND&date=" + today), tmNo)).isNull();

        // 受托厂成品到仓：后两批完工的精华液在可选列表里；受托厂送货不能选客户自提；没有记录温度，不符合
        JsonNode ins = read(prod, BASE + "/candidates?direction=INBOUND");
        assertThat(ins.size()).isGreaterThanOrEqualTo(2);
        JsonNode in1 = ins.get(0);
        assertThat(in1.get("partyName").asText()).isEqualTo("禾川生物（示例）");
        assertThat(in1.get("required").asBoolean()).isTrue();
        call(prod, BASE + "/records", rec("INBOUND", List.of(in1.get("docId").asLong()), "PICKUP", null, shipped, false, null, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("客户自提只用于销售发运")));
        JsonNode cold = ok(call(prod, BASE + "/records", rec("INBOUND", List.of(in1.get("docId").asLong()), "VEHICLE", "禾川货车", shipped, false, null, null)));
        assertThat(cold.get("conforming").asBoolean()).isFalse();
        assertThat(cold.get("problem").asText()).isEqualTo("要求记录途中温度，但没有记录");
        assertThat(cold.get("docs").get(0).get("batchesText").asText()).isNotBlank();

        // 客户自提：运输由客户负责，不要求记录温度；到货异常要写情况
        long hd = id(qa, "/api/partners", "CUS-HD");
        long serum = id(qa, "/api/items", "FG-SER-001");
        JsonNode so = ok(call(sales, "/api/sales-orders", Map.of("customerId", hd, "warehouseId", main, "lines", List.of(Map.of("itemId", serum, "qty", "10")))));
        Map<String, Object> pickup = rec("OUTBOUND", List.of(so.get("id").asLong()), "PICKUP", null, shipped, false, null, null);
        pickup.put("damaged", true);
        call(sales, BASE + "/records", pickup).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("到货异常的情况")));
        pickup.put("damaged", false);
        JsonNode pk = ok(call(sales, BASE + "/records", pickup));
        assertThat(pk.get("conforming").asBoolean()).isTrue();
        assertThat(pk.get("deviationNo").isNull()).isTrue();

        // 作废：只给质量部门；作废后这张单据可以重新登记，不符合的待办消失，偏差不自动关
        long hotId = hot.get("id").asLong();
        call(wh, BASE + "/records/" + hotId + "/void", Map.of("reason", "录错温度")).andExpect(status().isForbidden());
        call(qa, BASE + "/records/" + hotId + "/void", Map.of("reason", " ")).andExpect(status().isBadRequest());
        JsonNode voided = ok(call(qa, BASE + "/records/" + hotId + "/void", Map.of("reason", "记录仪读数抄错，重新登记")));
        assertThat(voided.get("voided").asBoolean()).isTrue();
        assertThat(voided.get("deviationStatus").asText()).isEqualTo("OPEN");
        call(qa, BASE + "/records/" + hotId + "/void", Map.of("reason", "再作废一次")).andExpect(status().isBadRequest());
        assertThat(candidate(read(sales, BASE + "/candidates?direction=OUTBOUND&date=" + today), tmNo)).isNotNull();
        assertThat(todos(qso)).doesNotContain(hot.get("recordNo").asText()).contains(cold.get("recordNo").asText());
        assertThat(read(login("admin"), "/api/audit?q=作废运输记录").toString()).contains("请质量部门评估后关闭");

        // 自查第 20 项：登记期限设成 0 天，天猫那单（作废后）、还有一批到仓都缺运输记录，到仓那条不符合、偏差没关
        grace(0);
        long checkId = ok(call(qa, "/api/qms/self-checks", Map.of("periodFrom", today.minusDays(30).toString(), "periodTo", today.toString())))
                .get("check").get("id").asLong();
        Map<Integer, JsonNode> ev = new HashMap<>();
        read(qa, "/api/qms/self-checks/" + checkId + "/evidence").forEach(e -> ev.put(e.get("itemNo").asInt(), e));
        JsonNode e20 = ev.get(20);
        assertThat(e20.get("level").asText()).isEqualTo("WARN");
        assertThat(e20.get("summary").asText()).contains("设了运输条件的成品 2 个").contains("单超过 0 天没有运输记录")
                .contains("不符合 1 条，其中 1 条的偏差还没关闭").doesNotContain("运输条件请人工核对");
        assertThat(e20.get("details").toString()).contains("出库的 1 张单据有设了运输条件的成品，没有运输记录").contains("完工入库，没有到仓运输记录")
                .contains(cold.get("recordNo").asText());

        // 导出
        mvc.perform(get("/api/reports/transport-records?from=" + today.minusDays(30) + "&to=" + today).header("Authorization", "Bearer " + viewer))
                .andExpect(status().isOk());
    }
}
