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
import java.util.ArrayList;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 贮存区域和温湿度记录（检查要点委托生产版第 16、20 项）。会生成偏差、影响总览和自查核对，用单独的库。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-storage-env;MODE=MySQL;DB_CLOSE_DELAY=-1")
class StorageMonitorTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;
    private static final String BASE = "/api/storage-env";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TimeProvider time;

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

    private JsonNode area(String t, String code) throws Exception {
        for (JsonNode a : read(t, BASE + "/areas")) {
            if (a.get("code").asText().equals(code)) {
                return a;
            }
        }
        throw new AssertionError(code);
    }

    private JsonNode excursion(String t, String no) throws Exception {
        for (JsonNode e : read(t, BASE + "/excursions")) {
            if (e.get("excursionNo").asText().equals(no)) {
                return e;
            }
        }
        throw new AssertionError(no);
    }

    private static Map<String, Object> areaReq(String code, String name, String kind, String tMin, String tMax, String rMin, String rMax, LocalDate from) {
        Map<String, Object> m = new HashMap<>();
        m.put("code", code);
        m.put("name", name);
        m.put("kind", kind);
        m.put("tempMin", tMin);
        m.put("tempMax", tMax);
        m.put("rhMin", rMin);
        m.put("rhMax", rMax);
        m.put("dailyRequired", true);
        m.put("monitorFrom", from.toString());
        return m;
    }

    private static Map<String, Object> rec(LocalDateTime at, String temp, String rh) {
        Map<String, Object> m = new HashMap<>();
        m.put("recordedAt", at.toString());
        m.put("temp", temp);
        m.put("rh", rh);
        return m;
    }

    @Test
    void areasRecordsExcursionsAndEvidence() throws Exception {
        String qa = login("qa1");
        String qso = login("qso1");
        String wh = login("purchase1");
        String viewer = login("viewer1");
        String sales = login("sales1");
        String admin = login("admin");
        LocalDate today = time.today();

        // 示例数据：主仓常温区 5 天前下午超温、第二天上午还超、下午恢复，一次超限一份偏差；留样室都在范围内
        JsonNode demoWh = area(qa, "WH-MAIN-A");
        assertThat(demoWh.get("missing").asBoolean()).isFalse();
        assertThat(demoWh.get("lastInRange").asBoolean()).isTrue();
        assertThat(demoWh.get("limitText").asText()).isEqualTo("温度 10–30℃，湿度 ≤75%");
        JsonNode demoEx = read(qa, BASE + "/excursions").get(0);
        assertThat(demoEx.get("areaName").asText()).isEqualTo("华东主仓常温区（示例）");
        assertThat(demoEx.get("readingCount").asInt()).isEqualTo(2);
        assertThat(demoEx.get("peakText").asText()).isEqualTo("温度 30.8–31.6℃");
        assertThat(demoEx.get("stillOut").asBoolean()).isFalse();
        assertThat(demoEx.get("deviationStatus").asText()).isEqualTo("OPEN");
        JsonNode demoDetail = read(qa, BASE + "/excursions/" + demoEx.get("id").asLong());
        assertThat(demoDetail.get("readings")).hasSize(2);
        assertThat(demoDetail.get("affected").size()).isPositive();
        assertThat(demoDetail.get("affectedNote").asText()).contains("华东主仓");
        assertThat(area(qa, "RS-01").get("kind").asText()).isEqualTo("RETAIN");

        // 权限：仓库不能设区域，只读不能录，销售不能作废
        Map<String, Object> cold = areaReq("T-COLD", "冷藏柜", "OTHER", "2", "8", null, null, today.minusDays(3));
        call(wh, BASE + "/areas", cold).andExpect(status().isForbidden());
        call(viewer, BASE + "/areas/" + demoWh.get("id").asLong() + "/records", rec(today.atTime(0, 1), "20", "50")).andExpect(status().isForbidden());

        // 区域：至少一个限度、下限低于上限、编码不重复
        call(qa, BASE + "/areas", areaReq("T-X", "无范围", "OTHER", null, null, null, null, today)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("至少设定")));
        call(qa, BASE + "/areas", areaReq("T-X", "反了", "OTHER", "8", "2", null, null, today)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("温度下限要低于上限")));
        long coldId = ok(call(qa, BASE + "/areas", cold)).get("id").asLong();
        call(qa, BASE + "/areas", cold).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("已存在")));
        String coldUrl = BASE + "/areas/" + coldId + "/records";

        // 录入：在范围内；缺温度、晚于现在、同一分钟重复都拒绝
        LocalDateTime d3 = today.minusDays(3).atStartOfDay();
        assertThat(ok(call(wh, coldUrl, rec(d3.plusHours(9), "5", null))).get("inRange").asBoolean()).isTrue();
        call(wh, coldUrl, rec(d3.plusHours(10), null, "50")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("请填写温度")));
        call(wh, coldUrl, rec(time.now().plusHours(1), "5", null)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不能晚于现在")));
        call(wh, coldUrl, rec(d3.plusHours(9).plusSeconds(30), "5", null)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("已有记录")));

        // 超限：生成一次超限和偏差；24 小时内的并进同一次
        JsonNode out1 = ok(call(wh, coldUrl, rec(d3.plusHours(15), "9.5", null)));
        assertThat(out1.get("inRange").asBoolean()).isFalse();
        assertThat(out1.get("problem").asText()).isEqualTo("温度 9.5℃ 高于上限 8℃");
        String e1 = out1.get("excursionNo").asText();
        String dv1 = out1.get("deviationNo").asText();
        assertThat(e1).startsWith("TH");
        JsonNode out2 = ok(call(wh, coldUrl, rec(d3.plusHours(18), "10.2", null)));
        assertThat(out2.get("excursionNo").asText()).isEqualTo(e1);
        assertThat(out2.get("deviationNo").asText()).isEqualTo(dv1);
        JsonNode dev = null;
        for (JsonNode d : read(qa, "/api/qms/deviations")) {
            if (d.get("deviationNo").asText().equals(dv1)) {
                dev = d;
            }
        }
        assertThat(dev).isNotNull();
        assertThat(dev.get("area").asText()).isEqualTo("WAREHOUSE");
        assertThat(dev.get("level").asText()).isEqualTo("MINOR");
        assertThat(dev.get("title").asText()).isEqualTo("温湿度超限：冷藏柜");
        assertThat(dev.get("description").asText()).contains("温度 9.5℃ 高于上限 8℃").contains("范围：温度 2–8℃");
        assertThat(todos(qa)).contains("贮存区域温湿度超限").contains("T-COLD").contains("/storage-env?tab=excursions");
        assertThat(excursion(qa, e1).get("stillOut").asBoolean()).isTrue();
        ok(call(wh, coldUrl, rec(today.minusDays(2).atTime(9, 0), "6", null)));
        assertThat(excursion(qa, e1).get("stillOut").asBoolean()).isFalse();
        assertThat(todos(qa)).doesNotContain("贮存区域温湿度超限");

        // 离上一次超限超过 24 小时：偏差虽然没关，也是新的一次
        JsonNode out3 = ok(call(wh, coldUrl, rec(today.minusDays(1).atTime(12, 0), "1", null)));
        String e2 = out3.get("excursionNo").asText();
        assertThat(e2).isNotEqualTo(e1);
        assertThat(out3.get("problem").asText()).isEqualTo("温度 1℃ 低于下限 2℃");
        // 偏差关闭以后再超限，即使在 24 小时内也是新的一次
        long dv2Id = out3.get("deviationId").asLong();
        ok(call(qa, "/api/qms/deviations/" + dv2Id + "/investigate", Map.of("rootCause", "开门取货时间过长")));
        ok(call(qa, "/api/qms/deviations/" + dv2Id + "/close", Map.of("conclusion", "评估不影响产品", "password", PWD)));
        JsonNode out4 = ok(call(wh, coldUrl, rec(today.minusDays(1).atTime(13, 0), "1.5", null)));
        String e3 = out4.get("excursionNo").asText();
        assertThat(e3).isNotEqualTo(e2);

        // 作废：只有质量部门；这次超限只有这一条，作废后超限也作废，同一分钟可以重录
        call(wh, coldUrl + "/" + out4.get("id").asLong() + "/void", Map.of("reason", "录错")).andExpect(status().isForbidden());
        call(sales, coldUrl + "/" + out4.get("id").asLong() + "/void", Map.of("reason", "录错")).andExpect(status().isForbidden());
        call(qa, coldUrl + "/" + out4.get("id").asLong() + "/void", Map.of()).andExpect(status().isBadRequest());
        JsonNode voided = ok(call(qa, coldUrl + "/" + out4.get("id").asLong() + "/void", Map.of("reason", "温度计没放进柜子，读的是室外")));
        assertThat(voided.get("voided").asBoolean()).isTrue();
        assertThat(excursion(qa, e3).get("voided").asBoolean()).isTrue();
        call(qa, coldUrl + "/" + out4.get("id").asLong() + "/void", Map.of("reason", "再来")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("已经作废")));
        // 记录路径带区域 id：别的区域的记录当不存在
        call(qa, BASE + "/areas/" + demoWh.get("id").asLong() + "/records/" + out1.get("id").asLong() + "/void", Map.of("reason", "x"))
                .andExpect(status().isNotFound());
        assertThat(ok(call(wh, coldUrl, rec(today.minusDays(1).atTime(13, 0), "4", null))).get("inRange").asBoolean()).isTrue();
        assertThat(ok(mvc.perform(get("/api/audit?q=T-COLD").header("Authorization", "Bearer " + admin))).toString())
                .contains("作废温湿度记录").contains("温度计没放进柜子").contains(e3 + " 已没有有效的超限记录");

        // 导入：先校验（文件里同一分钟重复、缺温度、湿度超出 0–100、晚于现在都按行报），任何一行有问题都不写
        List<Map<String, Object>> bad = new ArrayList<>();
        bad.add(rec(today.minusDays(2).atTime(10, 0), "5.04", null));
        bad.add(rec(today.minusDays(2).atTime(10, 0), "5.1", null));
        bad.add(rec(today.minusDays(2).atTime(11, 0), null, "50"));
        bad.add(rec(today.minusDays(2).atTime(12, 0), "5", "120"));
        bad.add(rec(time.now().plusDays(1), "5", null));
        JsonNode badRes = ok(call(wh, coldUrl + "/import", Map.of("rows", bad, "dryRun", false)));
        assertThat(badRes.get("applied").asBoolean()).isFalse();
        assertThat(badRes.get("errors").toString()).contains("和第 2 行是同一分钟").contains("请填写温度").contains("相对湿度在 0–100% 之间").contains("时间晚于现在");
        assertThat(badRes.get("errors").get(0).get("line").asInt()).isEqualTo(3);
        long before = read(qa, BASE + "/records?areaId=" + coldId + "&from=" + today.minusDays(7) + "&to=" + today).get("total").asLong();

        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(rec(today.minusDays(2).atTime(10, 0), "5.04", null));
        rows.add(rec(today.minusDays(2).atTime(9, 0), "6", null));      // 已有，跳过
        rows.add(rec(today.minusDays(2).atTime(11, 0), "12.26", null)); // 超限，和第一次超限相隔不到 24 小时、偏差没关，并进去
        JsonNode dry = ok(call(wh, coldUrl + "/import", Map.of("rows", rows, "dryRun", true)));
        assertThat(dry.get("created").asInt()).isEqualTo(2);
        assertThat(dry.get("skipped").asInt()).isEqualTo(1);
        assertThat(dry.get("outOfRange").asInt()).isEqualTo(1);
        assertThat(dry.get("newExcursions").asInt()).isZero();
        assertThat(read(qa, BASE + "/records?areaId=" + coldId + "&from=" + today.minusDays(7) + "&to=" + today).get("total").asLong()).isEqualTo(before);
        JsonNode applied = ok(call(wh, coldUrl + "/import", Map.of("rows", rows, "dryRun", false)));
        assertThat(applied.get("applied").asBoolean()).isTrue();
        assertThat(applied.get("created").asInt()).isEqualTo(2);
        JsonNode ex1 = excursion(qa, e1);
        assertThat(ex1.get("readingCount").asInt()).isEqualTo(3);
        assertThat(ex1.get("peakText").asText()).isEqualTo("温度 9.5–12.3℃");
        JsonNode list = read(qa, BASE + "/records?areaId=" + coldId + "&from=" + today.minusDays(2) + "&to=" + today.minusDays(2));
        assertThat(list.get("rows").toString()).contains("\"temp\":5.0").contains("记录仪导入");
        // 再导一次同样的文件：都已有，全部跳过
        JsonNode again = ok(call(wh, coldUrl + "/import", Map.of("rows", rows, "dryRun", false)));
        assertThat(again.get("created").asInt()).isZero();
        assertThat(again.get("skipped").asInt()).isEqualTo(3);

        // 修改范围记前后值；旧记录按当时的范围显示
        Map<String, Object> wider = areaReq("T-COLD", "冷藏柜", "OTHER", "2", "10", null, null, today.minusDays(3));
        wider.put("active", true);
        ok(mvc.perform(put(BASE + "/areas/" + coldId).header("Authorization", "Bearer " + qa).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(wider))));
        assertThat(ok(mvc.perform(get("/api/audit?q=T-COLD").header("Authorization", "Bearer " + admin))).toString()).contains("修改贮存区域").contains("温度上限 8 → 10");
        assertThat(list.get("rows").get(0).get("limitText").asText()).isEqualTo("温度 2–8℃");
        JsonNode trend = read(qa, BASE + "/areas/" + coldId + "/trend?from=" + today.minusDays(3) + "&to=" + today);
        assertThat(trend.get("tempMax").asDouble()).isEqualTo(10.0);
        assertThat(trend.get("points").size()).isEqualTo(8);
        mvc.perform(get(BASE + "/areas/" + coldId + "/trend?from=" + today.minusDays(200) + "&to=" + today).header("Authorization", "Bearer " + qa))
                .andExpect(status().isBadRequest());

        // 漏记：要求每天记录、昨天没有记录的区域进总览待办
        long gapId = ok(call(qa, BASE + "/areas", areaReq("T-GAP", "阴凉库", "WAREHOUSE", null, "20", null, null, today.minusDays(2)))).get("id").asLong();
        assertThat(area(qa, "T-GAP").get("missing").asBoolean()).isTrue();
        assertThat(area(qa, "T-COLD").get("missing").asBoolean()).isFalse();
        assertThat(todos(qa)).contains("温湿度漏记").contains("T-GAP").contains("还没有记录过");

        // 自查核对：第 20 项看仓储区域（阴凉库漏记 2 天、两次超限的偏差没关），第 16 项看留样室（示例都在范围内）
        long checkId = ok(call(qa, "/api/qms/self-checks", Map.of("periodFrom", today.minusDays(7).toString(), "periodTo", today.toString())))
                .get("check").get("id").asLong();
        Map<Integer, JsonNode> ev = new HashMap<>();
        read(qa, "/api/qms/self-checks/" + checkId + "/evidence").forEach(e -> ev.put(e.get("itemNo").asInt(), e));
        assertThat(ev.get(20).get("level").asText()).isEqualTo("WARN");
        assertThat(ev.get(20).get("summary").asText()).contains("1 个有漏记").contains("次超限的偏差还没关闭").contains("运输条件请人工核对");
        assertThat(ev.get(20).get("details").toString()).contains("阴凉库（T-GAP）").contains("漏记 2 天").contains(e1 + "（偏差 " + dv1 + " 未关闭）")
                .contains("华东主仓常温区（示例）");
        assertThat(ev.get(16).get("summary").asText()).contains("1 个留样区域的温湿度记录没有漏记，期间没有超限");
        assertThat(ev.get(16).get("details").toString()).contains("品质部留样室（RS-01）");
        assertThat(ev.get(16).get("details").toString()).doesNotContain("T-COLD");

        // 停用后不进待办、不能再录
        Map<String, Object> off = areaReq("T-GAP", "阴凉库", "WAREHOUSE", null, "20", null, null, today.minusDays(2));
        off.put("active", false);
        ok(mvc.perform(put(BASE + "/areas/" + gapId).header("Authorization", "Bearer " + qso).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(off))));
        assertThat(todos(qa)).doesNotContain("T-GAP");
        call(wh, BASE + "/areas/" + gapId + "/records", rec(today.atStartOfDay(), "15", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("已停用")));

        // 导出
        mvc.perform(get("/api/reports/env-records?from=" + today.minusDays(7) + "&to=" + today).header("Authorization", "Bearer " + qa)).andExpect(status().isOk());
        mvc.perform(get("/api/reports/env-excursions").header("Authorization", "Bearer " + viewer)).andExpect(status().isOk());
    }
}
