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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.SourceType;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.plm.StabilityService;
import com.cosmetic.scm.production.MfgOrder;
import com.cosmetic.scm.production.MfgOrderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 稳定性试验。限度和结果都是测试数据。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StabilityStudyFlowTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TimeProvider time;
    @Autowired BatchRepository batches;
    @Autowired MfgOrderRepository orders;

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

    private JsonNode read(String t, String url) throws Exception {
        return body(mvc.perform(get(url).header("Authorization", "Bearer " + t)).andExpect(status().isOk()));
    }

    /** 建一个有生效配方的成品，保质期 36 个月。 */
    private long product(String admin, String code) throws Exception {
        long m = body(call(admin, "/api/items", Map.of("code", code + "-RM", "name", "原料" + code, "itemType", "RM", "unit", "kg", "banned", false, "active", true, "shelfMonths", 24)).andExpect(status().isOk())).get("id").asLong();
        long f = body(call(admin, "/api/items", Map.of("code", code, "name", "成品" + code, "itemType", "FG", "unit", "瓶", "banned", false, "active", true, "shelfMonths", 36, "netG", 100, "filingNo", "备案" + code)).andExpect(status().isOk())).get("id").asLong();
        mvc.perform(put("/api/boms/" + f).header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("lines", List.of(line(m, "0.01")))))).andExpect(status().isOk());
        return f;
    }

    private Map<String, Object> line(long m, String qty) {
        return Map.of("itemId", m, "qtyPer", new BigDecimal(qty), "supplyMode", "BRAND");
    }

    private Map<String, Object> study(long fg, LocalDate start, int... months) {
        List<Map<String, Object>> times = new ArrayList<>();
        for (int m : months) {
            times.add(Map.of("months", m, "days", 0));
        }
        Map<String, Object> s = new HashMap<>(Map.of("fgItemId", fg, "studyType", "LONG_TERM", "sampleRef", "小试样品", "condition", "测试条件", "startDate", start.toString(), "timepoints", times,
                "tests", List.of(Map.of("name", "外观"), Map.of("name", "pH", "minValue", new BigDecimal("5"), "maxValue", new BigDecimal("7")))));
        return s;
    }

    private JsonNode create(String qa, long fg, LocalDate start, int... months) throws Exception {
        return body(call(qa, "/api/stability/studies", study(fg, start, months)).andExpect(status().isOk()));
    }

    /** 结果：外观人工判定、pH 系统判定。 */
    private Map<String, Object> result(JsonNode d, LocalDate date, String ph, boolean looksOk, String note) {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(Map.of("testId", d.get("tests").get(0).get("id").asLong(), "text", looksOk ? "正常" : "分层", "passed", looksOk));
        rows.add(Map.of("testId", d.get("tests").get(1).get("id").asLong(), "value", new BigDecimal(ph)));
        Map<String, Object> m = new HashMap<>(Map.of("doneDate", date.toString(), "results", rows));
        if (note != null) {
            m.put("note", note);
        }
        return m;
    }

    private String pullUrl(JsonNode d, int i, String action) {
        return "/api/stability/studies/" + d.get("study").get("id").asLong() + "/pulls/" + d.get("pulls").get(i).get("id").asLong() + "/" + action;
    }

    private String studyUrl(JsonNode d, String action) {
        return "/api/stability/studies/" + d.get("study").get("id").asLong() + "/" + action;
    }

    private JsonNode coverageOf(String t, long fg) throws Exception {
        for (JsonNode p : read(t, "/api/stability/products")) {
            if (p.get("fgItemId").asLong() == fg) {
                return p;
            }
        }
        throw new AssertionError(fg);
    }

    @Test
    void flowFromPlanToSignedConclusionAndCoverage() throws Exception {
        String admin = login("admin", "Admin@123456");
        String qa = login("qa1");
        String qso = login("qso1");
        long f = product(admin, "ST-FG1");
        LocalDate today = time.today();
        assertThat(coverageOf(qa, f).get("state").asText()).isEqualTo("NONE");

        // 岗位和校验
        call(login("sales1"), "/api/stability/studies", study(f, today, 0)).andExpect(status().isForbidden());
        call(qa, "/api/stability/studies", study(f, today.plusDays(1), 0)).andExpect(status().isBadRequest());
        call(qa, "/api/stability/studies", study(f, today, 3, 3)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("重复")));
        Map<String, Object> noSample = study(f, today, 0);
        noSample.remove("sampleRef");
        call(qa, "/api/stability/studies", noSample).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("样品")));
        Map<String, Object> badLimits = study(f, today, 0);
        badLimits.put("tests", List.of(Map.of("name", "pH", "minValue", 7, "maxValue", 5)));
        call(qa, "/api/stability/studies", badLimits).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("下限大于上限")));

        // 计划日期按月算，月底不溢出
        LocalDate start = today.minusMonths(3);
        JsonNode d = create(qa, f, start, 0, 3, 6);
        assertThat(d.get("study").get("status").asText()).isEqualTo("ACTIVE");
        assertThat(d.get("pulls").get(1).get("dueDate").asText()).isEqualTo(start.plusMonths(3).toString());
        assertThat(d.get("pulls").get(0).get("label").asText()).isEqualTo("起始");
        assertThat(d.get("pulls").get(2).get("label").asText()).isEqualTo("6 个月");
        assertThat(coverageOf(qa, f).get("state").asText()).isEqualTo("RUNNING");

        // 录结果：缺项目、人工项不判定、限度项不填数字都不行
        call(qso, pullUrl(d, 0, "record"), Map.of("doneDate", start.toString(), "results", List.of(Map.of("testId", d.get("tests").get(0).get("id").asLong(), "text", "正常", "passed", true))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("pH")));
        call(login("purchase1"), pullUrl(d, 0, "record"), result(d, start, "6", true, null)).andExpect(status().isForbidden());
        // 6 个月的取不了：时间不够的结果不能作依据
        call(qa, pullUrl(d, 2, "record"), result(d, today, "6", true, null)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("时间不够")));
        // 窗口内可以
        call(qa, pullUrl(d, 0, "record"), result(d, start.plusDays(StabilityService.WINDOW_DAYS), "6", true, null)).andExpect(status().isOk());
        // 提交后不能改
        call(qa, pullUrl(d, 0, "record"), result(d, start, "6.5", true, null)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("请申请结果更正")));
        JsonNode after = call3(qa, d, today);
        assertThat(after.get("pulls").get(1).get("passed").asBoolean()).isTrue();
        // 系统判定的项目，填“合格”不算数在下一个测试里验

        // 没做完不能下结论
        call(qa, studyUrl(d, "conclude"), Map.of("conclusion", "x", "supportedMonths", 36)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("6 个月")));
        call(qa, pullUrl(d, 2, "skip"), Map.of("note", "样品不够")).andExpect(status().isOk());
        call(qa, studyUrl(d, "conclude"), Map.of("conclusion", "3 个月内无变化，支持 24 个月", "supportedMonths", 24)).andExpect(status().isOk());
        assertThat(read(qa, "/api/stability/studies/" + d.get("study").get("id").asLong()).get("study").get("status").asText()).isEqualTo("REVIEW");
        assertThat(body(mvc.perform(get("/api/dashboard").header("Authorization", "Bearer " + qa))).toString()).contains("稳定性结论待签批");

        // 只有 QSO 能签，要密码；退回后可以重写
        call(qa, studyUrl(d, "approve"), Map.of("password", PWD)).andExpect(status().isForbidden());
        call(qso, studyUrl(d, "approve"), Map.of("password", "wrong")).andExpect(status().isBadRequest());
        call(qso, studyUrl(d, "reject"), Map.of("note", "依据不足")).andExpect(status().isOk());
        assertThat(read(qa, "/api/dashboard").toString()).contains("稳定性试验待写结论");
        // 提交结论的人不能自己签
        call(qso, studyUrl(d, "conclude"), Map.of("conclusion", "重写", "supportedMonths", 24)).andExpect(status().isOk());
        call(qso, studyUrl(d, "approve"), Map.of("password", PWD)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("提交结论")));
        call(qso, studyUrl(d, "reject"), Map.of("note", "换人写")).andExpect(status().isOk());
        call(qa, studyUrl(d, "conclude"), Map.of("conclusion", "3 个月内无变化，支持 24 个月", "supportedMonths", 24)).andExpect(status().isOk());
        call(qso, studyUrl(d, "approve"), Map.of("password", PWD)).andExpect(status().isOk());

        // 依据 24 个月，标的 36 个月：提醒
        JsonNode c = coverageOf(qa, f);
        assertThat(c.get("state").asText()).isEqualTo("SHORT");
        assertThat(c.get("supportedMonths").asInt()).isEqualTo(24);
        assertThat(read(qa, "/api/dashboard").toString()).contains("保质期超过稳定性依据").contains("ST-FG1");
        assertThat(read(admin, "/api/golive").toString()).contains("稳定性依据");

        // 作废只给 QSO，依据随之失效
        call(qa, studyUrl(d, "void"), Map.of("note", "x")).andExpect(status().isForbidden());
        call(qso, studyUrl(d, "void"), Map.of("note", "报告撤回")).andExpect(status().isOk());
        assertThat(coverageOf(qa, f).get("state").asText()).isEqualTo("NONE");
        call(qso, studyUrl(d, "void"), Map.of("note", "x")).andExpect(status().isBadRequest());
    }

    private JsonNode call3(String qa, JsonNode d, LocalDate date) throws Exception {
        return body(call(qa, pullUrl(d, 1, "record"), result(d, date, "6.2", true, null)).andExpect(status().isOk()));
    }

    @Test
    void failuresRaiseADeviationThatBlocksTheSignatureAndTheBatch() throws Exception {
        String admin = login("admin", "Admin@123456");
        String qa = login("qa1");
        String qso = login("qso1");
        long f = product(admin, "ST-FG2");
        LocalDate today = time.today();
        Batch b = new Batch();
        b.setBatchNo("ST-BATCH-1");
        b.setItemId(f);
        b.setMfgDate(today.minusMonths(1));
        b.setExpDate(today.plusYears(2));
        b.setStatus(BatchStatus.QUARANTINE);
        b.setSourceType(SourceType.OPEN);
        b.setSourceNo("ST-OPEN");
        b.setQtyIn(BigDecimal.TEN);
        b.setCreatedAt(time.now());
        batches.save(b);

        // 批号必须是这个成品的
        Map<String, Object> wrong = study(f, today, 0);
        wrong.put("batchNo", "不存在");
        call(qa, "/api/stability/studies", wrong).andExpect(status().isBadRequest());
        Map<String, Object> s = study(f, today.minusMonths(1), 0, 1);
        s.put("batchNo", "ST-BATCH-1");
        s.remove("sampleRef");
        JsonNode d = body(call(qa, "/api/stability/studies", s).andExpect(status().isOk()));
        assertThat(d.get("study").get("batchNo").asText()).isEqualTo("ST-BATCH-1");
        long other = product(admin, "ST-FG3");
        Map<String, Object> notMine = study(other, today, 0);
        notMine.put("batchNo", "ST-BATCH-1");
        call(qa, "/api/stability/studies", notMine).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不是")));

        // 超限：系统判定，人工填“合格”不算数；要写现场情况
        LocalDate date = today.minusMonths(1);
        List<Map<String, Object>> cheat = List.of(Map.of("testId", d.get("tests").get(0).get("id").asLong(), "text", "正常", "passed", true),
                Map.of("testId", d.get("tests").get(1).get("id").asLong(), "value", new BigDecimal("8.5"), "passed", true));
        call(qa, pullUrl(d, 0, "record"), Map.of("doneDate", date.toString(), "results", cheat)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("现场情况")));
        JsonNode r = body(call(qa, pullUrl(d, 0, "record"), Map.of("doneDate", date.toString(), "results", cheat, "note", "pH 偏高")).andExpect(status().isOk()));
        JsonNode pull = r.get("pulls").get(0);
        assertThat(pull.get("passed").asBoolean()).isFalse();
        assertThat(pull.get("deviationNo").asText()).startsWith("DV");

        // 偏差挂在批次上，重大级别，放行会被拦
        JsonNode dev = null;
        for (JsonNode x : read(qa, "/api/qms/deviations")) {
            if (x.get("id").asLong() == pull.get("deviationId").asLong()) {
                dev = x;
            }
        }
        assertThat(dev).isNotNull();
        assertThat(dev.get("batchNo").asText()).isEqualTo("ST-BATCH-1");
        assertThat(dev.get("level").asText()).isEqualTo("MAJOR");
        assertThat(dev.get("description").asText()).contains("8.5").contains("5 ~ 7").contains("pH 偏高");

        // 人工项判不合格也生成偏差
        JsonNode r2 = body(call(qa, pullUrl(d, 1, "record"), result(d, today, "6", false, "分层了")).andExpect(status().isOk()));
        assertThat(r2.get("pulls").get(1).get("deviationNo").isNull()).isFalse();
        assertThat(r2.get("study").get("failed").asInt()).isEqualTo(2);

        // 偏差没关不能签批
        call(qa, studyUrl(d, "conclude"), Map.of("conclusion", "不支持", "supportedMonths", 0)).andExpect(status().isOk());
        call(qso, studyUrl(d, "approve"), Map.of("password", PWD)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("没关闭")));
        for (JsonNode p : r2.get("pulls")) {
            long id = p.get("deviationId").asLong();
            call(qa, "/api/qms/deviations/" + id + "/investigate", Map.of("rootCause", "测试")).andExpect(status().isOk());
            // 重大偏差要有 CAPA，完成人和验证人不能是同一个
            long capa = body(call(qa, "/api/qms/capas", Map.of("sourceType", "DEVIATION", "deviationId", id, "actionType", "CORRECTIVE", "description", "测试", "owner", "qa1", "dueDate", today.plusDays(7).toString())).andExpect(status().isOk())).get("id").asLong();
            call(qa, "/api/qms/capas/" + capa + "/done", Map.of("note", "完成")).andExpect(status().isOk());
            call(qso, "/api/qms/capas/" + capa + "/verify", Map.of("effective", true, "note", "有效", "password", PWD)).andExpect(status().isOk());
            call(qso, "/api/qms/deviations/" + id + "/close", Map.of("conclusion", "测试结束", "password", PWD)).andExpect(status().isOk());
        }
        call(qso, studyUrl(d, "approve"), Map.of("password", PWD)).andExpect(status().isOk());
        assertThat(coverageOf(qa, f).get("state").asText()).isEqualTo("SHORT");

        // 导出：每个时间点每个项目一行，带判定和偏差号
        byte[] xlsx = mvc.perform(get("/api/reports/stability").header("Authorization", "Bearer " + login("viewer1"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        String sheet = "";
        try (var z = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(xlsx), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.getName().startsWith("xl/worksheets/")) {
                    sheet = new String(z.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        assertThat(sheet).contains(d.get("study").get("studyNo").asText()).contains("ST-BATCH-1").contains("8.5").contains("5 ~ 7").contains("不合格")
                .contains(pull.get("deviationNo").asText()).contains("pH 偏高");
    }

    @Test
    void lateAndCancelledAndOverdueReminders() throws Exception {
        String admin = login("admin", "Admin@123456");
        String qa = login("qa1");
        long f = product(admin, "ST-FG4");
        LocalDate today = time.today();
        JsonNode d = create(qa, f, today.minusMonths(2), 0, 1, 2);
        String no = d.get("study").get("studyNo").asText();

        // 超期的进紧急待办
        JsonNode todos = read(qa, "/api/dashboard").get("todos");
        boolean overdue = false;
        for (JsonNode t : todos) {
            overdue |= "STABILITY_DUE".equals(t.get("kind").asText()) && no.equals(t.get("ref").asText()) && "bad".equals(t.get("level").asText());
        }
        assertThat(overdue).isTrue();

        // 比计划晚超过窗口要写原因
        call(qa, pullUrl(d, 0, "record"), result(d, today, "6", true, null)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("原因")));
        call(qa, pullUrl(d, 0, "record"), result(d, today, "6", true, "仪器维修")).andExpect(status().isOk());
        // 不能写未来
        call(qa, pullUrl(d, 1, "record"), result(d, today.plusDays(1), "6", true, null)).andExpect(status().isBadRequest());
        // 不属于这次试验的项目和时间点
        JsonNode other = create(qa, f, today, 0);
        call(qa, "/api/stability/studies/" + other.get("study").get("id").asLong() + "/pulls/" + d.get("pulls").get(1).get("id").asLong() + "/record", result(other, today, "6", true, null)).andExpect(status().isNotFound());
        call(qa, pullUrl(d, 1, "record"), result(other, today, "6", true, "仪器维修")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不属于")));

        // 取消后不再提醒，也不能再录
        call(qa, studyUrl(d, "cancel"), Map.of("note", "方案调整")).andExpect(status().isOk());
        assertThat(read(qa, "/api/dashboard").get("todos").toString()).doesNotContain(no);
        call(qa, pullUrl(d, 1, "record"), result(d, today, "6", true, null)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("已取消")));
        call(qa, studyUrl(d, "cancel"), Map.of("note", "x")).andExpect(status().isBadRequest());
    }

    @Test
    void conclusionsBelongToAFormulaVersion() throws Exception {
        String admin = login("admin", "Admin@123456");
        String qa = login("qa1");
        String qso = login("qso1");
        long f = product(admin, "ST-FG5");
        LocalDate today = time.today();
        JsonNode d = create(qa, f, today, 0);
        call(qa, pullUrl(d, 0, "record"), result(d, today, "6", true, null)).andExpect(status().isOk());
        call(qa, studyUrl(d, "conclude"), Map.of("conclusion", "支持 36 个月", "supportedMonths", 36)).andExpect(status().isOk());
        call(qso, studyUrl(d, "approve"), Map.of("password", PWD)).andExpect(status().isOk());
        assertThat(coverageOf(qa, f).get("state").asText()).isEqualTo("OK");

        // 配方变成 V2：旧结论不算依据
        long m = 0;
        for (JsonNode i : read(admin, "/api/items")) {
            if ("ST-FG5-RM".equals(i.get("code").asText())) {
                m = i.get("id").asLong();
            }
        }
        long draft = body(call(admin, "/api/plm/formulas/" + f + "/draft", Map.of("changeNote", "调整用量", "lines", List.of(line(m, "0.02")))).andExpect(status().isOk())).get("id").asLong();
        call(admin, "/api/plm/versions/" + draft + "/submit", Map.of()).andExpect(status().isOk());
        long filing = body(call(qa, "/api/plm/filings", Map.of("fgItemId", f, "filingType", "GENERAL", "formulaVersionId", draft, "submittedDate", today.toString())).andExpect(status().isOk())).get("id").asLong();
        call(qa, "/api/plm/filings/" + filing + "/complete", Map.of("filingNo", "备案ST-FG5-V2", "filedDate", today.toString())).andExpect(status().isOk());
        call(qso, "/api/plm/versions/" + draft + "/approve", Map.of("password", PWD)).andExpect(status().isOk());
        assertThat(coverageOf(qa, f).get("state").asText()).isEqualTo("STALE");
        assertThat(read(qa, "/api/dashboard").toString()).contains("稳定性依据要重新做");

        // 批次是按哪个版本生产的，就只能作为哪个版本的依据
        MfgOrder mo = orders.findAll().stream().filter(x -> x.getFormulaVersionId() != null).findFirst().orElseThrow();
        Batch b = new Batch();
        b.setBatchNo("ST-BATCH-MO");
        b.setItemId(mo.getFgItemId());
        b.setMfgDate(today);
        b.setExpDate(today.plusYears(2));
        b.setStatus(BatchStatus.QUARANTINE);
        b.setSourceType(SourceType.MO);
        b.setSourceNo(mo.getMoNo());
        b.setQtyIn(BigDecimal.TEN);
        b.setCreatedAt(time.now());
        batches.save(b);
        Map<String, Object> s = study(mo.getFgItemId(), today, 0);
        s.put("batchNo", "ST-BATCH-MO");
        s.put("formulaVersionId", mo.getFormulaVersionId() + 100000);
        call(qa, "/api/stability/studies", s).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("另一个配方版本")));
        s.remove("formulaVersionId");
        JsonNode ok = body(call(qa, "/api/stability/studies", s).andExpect(status().isOk()));
        call(qa, "/api/stability/studies/" + ok.get("study").get("id").asLong() + "/cancel", Map.of("note", "测试结束")).andExpect(status().isOk());
    }

    private JsonNode pullOf(JsonNode d, String label) {
        for (JsonNode p : d.get("pulls")) {
            if (p.get("label").asText().equals(label)) {
                return p;
            }
        }
        throw new AssertionError(label);
    }

    private Map<String, Object> amend(String reason, List<?> add, List<?> cancel, List<?> tests) {
        return Map.of("reason", reason, "addTimepoints", add, "cancelPullIds", cancel, "addTests", tests, "password", PWD);
    }

    @Test
    void protocolAmendmentsAddAndCancelTimepointsAndAddTestsWithAReason() throws Exception {
        String admin = login("admin", "Admin@123456");
        String qa = login("qa1");
        long f = product(admin, "ST-AM");
        LocalDate start = time.today().minusMonths(1);
        JsonNode d = create(qa, f, start, 0, 1, 3);
        d = body(call(qa, pullUrl(d, 0, "record"), result(d, start, "6", true, null)).andExpect(status().isOk()));
        String url = studyUrl(d, "amend");
        long done = pullOf(d, "起始").get("id").asLong();
        long three = pullOf(d, "3 个月").get("id").asLong();

        call(login("prod1"), url, amend("改", List.of(Map.of("months", 6, "days", 0)), List.of(), List.of())).andExpect(status().isForbidden());
        call(qa, url, amend("改", List.of(), List.of(), List.of())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("没有要修改的内容")));
        call(qa, url, amend("改", List.of(), List.of(done), List.of())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("只有还没做的时间点能取消")));
        call(qa, url, amend("改", List.of(Map.of("months", 1, "days", 0)), List.of(), List.of())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("已经在方案里了")));
        call(qa, url, amend("改", List.of(), List.of(), List.of(Map.of("name", "pH", "minValue", 4)))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("要改限度请加一个新名称的项目")));
        call(qa, url, amend("改", List.of(), List.of(), List.of(Map.of("name", "粘度", "minValue", 9, "maxValue", 1)))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("下限大于上限")));
        // 取消掉的时间点可以重新加回来，在方案里的不行
        d = body(call(qa, url, amend("客户要求延长到 6 个月，3 个月样品不足", List.of(Map.of("months", 6, "days", 0)), List.of(three),
                List.of(Map.of("name", "粘度", "unit", "mPa·s", "minValue", 1000, "maxValue", 5000)))).andExpect(status().isOk()));
        assertThat(d.get("revNo").asInt()).isEqualTo(1);
        String summary = d.get("amendments").get(0).get("summary").asText();
        assertThat(summary).contains("加时间点：6 个月").contains("取消时间点：3 个月").contains("加检测项目：粘度（1000 ~ 5000 mPa·s）");
        assertThat(d.get("amendments").get(0).get("createdBy").asText()).isEqualTo("qa1");
        JsonNode cancelled = pullOf(d, "3 个月");
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("skipReason").asText()).startsWith("方案第 1 版取消：客户要求");
        assertThat(d.get("tests").size()).isEqualTo(3);
        assertThat(d.get("study").get("pullsTotal").asInt()).isEqualTo(4);
        // 取消的时间点不能再录结果；新加的项目之后每个时间点都要做
        call(qa, "/api/stability/studies/" + d.get("study").get("id").asLong() + "/pulls/" + three + "/record", result(d, time.today(), "6", true, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("方案取消")));
        long one = pullOf(d, "1 个月").get("id").asLong();
        call(qa, "/api/stability/studies/" + d.get("study").get("id").asLong() + "/pulls/" + one + "/record", result(d, time.today(), "6", true, null))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("请填写“粘度”的结果")));

        // 都取消掉也可以，已做的时间点还在；取消的不挡写结论
        long six = pullOf(d, "6 个月").get("id").asLong();
        d = body(call(qa, url, amend("样品用完", List.of(), List.of(one, six), List.of())).andExpect(status().isOk()));
        assertThat(d.get("revNo").asInt()).isEqualTo(2);
        body(call(qa, studyUrl(d, "conclude"), Map.of("conclusion", "只做了起始", "supportedMonths", 0)).andExpect(status().isOk()));
        call(qa, url, amend("再改", List.of(Map.of("months", 9, "days", 0)), List.of(), List.of())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不能修改方案")));
        assertThat(read(admin, "/api/audit?q=" + d.get("study").get("studyNo").asText()).toString()).contains("修改稳定性方案").contains("第 1 版").contains("客户要求延长到 6 个月");
    }

    private Map<String, Object> correct(JsonNode d, int test, String value, String text, Boolean passed, String reason) {
        Map<String, Object> m = new HashMap<>(Map.of("testId", d.get("tests").get(test).get("id").asLong(), "reason", reason));
        if (value != null) {
            m.put("value", new BigDecimal(value));
        }
        if (text != null) {
            m.put("text", text);
        }
        if (passed != null) {
            m.put("passed", passed);
        }
        return m;
    }

    @Test
    void correctionsNeedAnotherQsoKeepTheOriginalAndReJudge() throws Exception {
        String admin = login("admin", "Admin@123456");
        String qa = login("qa1");
        String qso = login("qso1");
        long f = product(admin, "ST-CR");
        LocalDate start = time.today().minusMonths(1);
        JsonNode d = create(qa, f, start, 0, 1);
        d = body(call(qa, pullUrl(d, 0, "record"), result(d, start, "6", true, null)).andExpect(status().isOk()));
        d = body(call(qa, pullUrl(d, 1, "record"), result(d, time.today(), "6.5", true, null)).andExpect(status().isOk()));
        String first = pullUrl(d, 0, "corrections");
        String second = pullUrl(d, 1, "corrections");

        call(login("sales1"), first, correct(d, 1, "7.5", null, null, "抄错")).andExpect(status().isForbidden());
        call(qa, first, correct(d, 1, "6.0", null, null, "抄错")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("和原来的结果一样")));
        call(qa, first, correct(d, 1, null, null, null, "抄错")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("请填写实测数值")));
        call(qa, first, correct(d, 1, "7.5", null, null, " ")).andExpect(status().isBadRequest());
        d = body(call(qa, first, correct(d, 1, "7.5", null, null, "原始记录是 7.5，录入时抄错")).andExpect(status().isOk()));
        JsonNode c = d.get("corrections").get(0);
        assertThat(c.get("status").asText()).isEqualTo("PENDING");
        assertThat(c.get("oldValue").decimalValue()).isEqualByComparingTo("6");
        assertThat(c.get("newPassed").asBoolean()).isFalse();
        // 结果还没变
        assertThat(d.get("pulls").get(0).get("passed").asBoolean()).isTrue();
        call(qa, first, correct(d, 1, "7.4", null, null, "再改")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("已有一个更正待审批")));
        call(qa, studyUrl(d, "conclude"), Map.of("conclusion", "结论", "supportedMonths", 1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("有结果更正待审批")));
        assertThat(read(qso, "/api/dashboard").toString()).contains("稳定性结果更正待审批");

        // 申请人本人不能批；QA 不能审批
        String approve = "/api/stability/corrections/" + c.get("id").asLong() + "/approve";
        call(qa, approve, Map.of("password", PWD)).andExpect(status().isForbidden());
        JsonNode own = body(call(qso, second, correct(d, 0, null, "轻微变色", false, "复看留样有变色")).andExpect(status().isOk())).get("corrections").get(1);
        call(qso, "/api/stability/corrections/" + own.get("id").asLong() + "/approve", Map.of("password", PWD)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("批准人不能是申请人")));
        body(call(qso, "/api/stability/corrections/" + own.get("id").asLong() + "/reject", Map.of("note", "要另一位复核")).andExpect(status().isOk()));
        call(qso, "/api/stability/corrections/" + own.get("id").asLong() + "/reject", Map.of("note", "再驳回")).andExpect(status().isBadRequest());

        // 批准：结果改为新值，按新值判定，改成不合格的生成偏差；原值留在更正记录里
        d = body(call(qso, approve, Map.of("password", PWD)).andExpect(status().isOk()));
        JsonNode r = d.get("pulls").get(0).get("results").get(1);
        assertThat(r.get("value").decimalValue()).isEqualByComparingTo("7.5");
        assertThat(r.get("passed").asBoolean()).isFalse();
        assertThat(r.get("corrected").asBoolean()).isTrue();
        assertThat(d.get("pulls").get(0).get("passed").asBoolean()).isFalse();
        assertThat(d.get("pulls").get(0).get("deviationNo").asText()).startsWith("DV");
        assertThat(d.get("corrections").get(0).get("status").asText()).isEqualTo("APPROVED");
        assertThat(d.get("corrections").get(0).get("decidedBy").asText()).isEqualTo("qso1");
        assertThat(d.get("study").get("failed").asInt()).isEqualTo(1);
        call(qso, approve, Map.of("password", PWD)).andExpect(status().isBadRequest());
        assertThat(read(admin, "/api/audit?q=" + d.get("study").get("studyNo").asText()).toString()).contains("批准稳定性结果更正").contains("6（合格） 改为 7.5（不合格）");

        // 合格改合格：不生成偏差
        long c2 = body(call(qa, second, correct(d, 1, "6.6", null, null, "仪器校准后复测")).andExpect(status().isOk())).get("corrections").get(2).get("id").asLong();
        d = body(call(qso, "/api/stability/corrections/" + c2 + "/approve", Map.of("password", PWD)).andExpect(status().isOk()));
        assertThat(d.get("pulls").get(1).get("deviationNo").isNull()).isTrue();

        // 结论待签批时不能更正，退回后可以；取消试验时待审批的更正一并驳回
        d = body(call(qa, studyUrl(d, "conclude"), Map.of("conclusion", "结论", "supportedMonths", 1)).andExpect(status().isOk()));
        call(qa, second, correct(d, 1, "6.7", null, null, "复测")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("先请质量安全负责人退回结论")));
        body(call(qso, studyUrl(d, "reject"), Map.of("note", "要更正")).andExpect(status().isOk()));
        body(call(qa, second, correct(d, 1, "6.7", null, null, "复测")).andExpect(status().isOk()));
        d = body(call(qa, studyUrl(d, "cancel"), Map.of("note", "不做了")).andExpect(status().isOk()));
        JsonNode last = d.get("corrections").get(3);
        assertThat(last.get("status").asText()).isEqualTo("REJECTED");
        assertThat(last.get("decideNote").asText()).isEqualTo("试验已取消");
    }
}
