package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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

/**
 * 质量标准和批次检验。项目和限度都是测试数据。
 * 用单独的库：这里建了没有配方的成品和待检批次，留在共用库里会让上线检查、主线计数的用例随执行顺序失败。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-spec;MODE=MySQL;DB_CLOSE_DELAY=-1")
class QualitySpecInspectionTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TimeProvider time;
    @Autowired BatchRepository batches;

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

    private long item(String admin, String code, String type) throws Exception {
        Map<String, Object> m = new HashMap<>(Map.of("code", code, "name", "物料" + code, "itemType", type, "unit", "RM".equals(type) ? "kg" : "瓶", "banned", false, "active", true, "shelfMonths", 24));
        if ("FG".equals(type)) {
            m.put("netG", 50);
            m.put("filingNo", "备案" + code);
        }
        return body(call(admin, "/api/items", m).andExpect(status().isOk())).get("id").asLong();
    }

    private Batch batch(long itemId, String no, BatchStatus st) {
        Batch b = new Batch();
        b.setBatchNo(no);
        b.setItemId(itemId);
        b.setMfgDate(time.today().minusDays(10));
        b.setExpDate(time.today().plusYears(2));
        b.setStatus(st);
        b.setSourceType(SourceType.OPEN);
        b.setSourceNo("QS-TEST");
        b.setQtyIn(BigDecimal.TEN);
        b.setCreatedAt(time.now());
        return batches.save(b);
    }

    private Map<String, Object> spec(long itemId, String note) {
        return Map.of("itemId", itemId, "changeNote", note, "lines", List.of(Map.of("name", "外观", "criteria", "测试"),
                Map.of("name", "含量", "unit", "%", "minValue", new BigDecimal("98"))));
    }

    private JsonNode effectiveSpec(String qa, String admin, String qso, long itemId) throws Exception {
        long id = body(call(qa, "/api/quality/specs", spec(itemId, "首版")).andExpect(status().isOk())).get("id").asLong();
        return body(call(qso, "/api/quality/specs/" + id + "/approve", Map.of("password", PWD)).andExpect(status().isOk()));
    }

    private Map<String, Object> inspect(JsonNode spec, String content, boolean looksOk, String note) {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(Map.of("lineId", spec.get("lines").get(0).get("id").asLong(), "text", looksOk ? "正常" : "浑浊", "passed", looksOk));
        rows.add(Map.of("lineId", spec.get("lines").get(1).get("id").asLong(), "value", new BigDecimal(content), "passed", true));
        Map<String, Object> m = new HashMap<>(Map.of("inspectedDate", time.today().toString(), "results", rows));
        if (note != null) {
            m.put("note", note);
        }
        return m;
    }

    private Map<String, Object> release(String report) {
        return Map.of("decision", "RELEASED", "reportNo", report, "note", "测试", "password", PWD);
    }

    @Test
    void specsAreVersionedAndSignedByAnotherQso() throws Exception {
        String admin = login("admin", "Admin@123456");
        String qa = login("qa1");
        String qso = login("qso1");
        long m = item(admin, "QS-RM1", "RM");

        call(login("purchase1"), "/api/quality/specs", spec(m, "首版")).andExpect(status().isForbidden());
        Map<String, Object> dup = Map.of("itemId", m, "changeNote", "x", "lines", List.of(Map.of("name", "pH"), Map.of("name", "pH")));
        call(qa, "/api/quality/specs", dup).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("重复")));
        Map<String, Object> bad = Map.of("itemId", m, "changeNote", "x", "lines", List.of(Map.of("name", "pH", "minValue", 7, "maxValue", 5)));
        call(qa, "/api/quality/specs", bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("下限大于上限")));

        long v1 = body(call(qa, "/api/quality/specs", spec(m, "首版")).andExpect(status().isOk())).get("id").asLong();
        // 同一物料只能有一个待签批
        call(qa, "/api/quality/specs", spec(m, "又一版")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("待签批")));
        assertThat(read(qa, "/api/dashboard").get("todos").toString()).contains("质量标准待签批").contains("QS-RM1");
        mvc.perform(get("/api/quality/items/" + m + "/spec").header("Authorization", "Bearer " + qa)).andExpect(status().isNoContent());

        call(qa, "/api/quality/specs/" + v1 + "/approve", Map.of("password", PWD)).andExpect(status().isForbidden());
        long own = 0;
        call(qso, "/api/quality/specs/" + v1 + "/reject", Map.of("note", "项目不全")).andExpect(status().isOk());
        own = body(call(qso, "/api/quality/specs", spec(m, "补全")).andExpect(status().isOk())).get("id").asLong();
        call(qso, "/api/quality/specs/" + own + "/approve", Map.of("password", PWD)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("提交人")));
        call(qso, "/api/quality/specs/" + own + "/reject", Map.of("note", "换人提交")).andExpect(status().isOk());
        long v3 = body(call(qa, "/api/quality/specs", spec(m, "补全")).andExpect(status().isOk())).get("id").asLong();
        JsonNode ok = body(call(qso, "/api/quality/specs/" + v3 + "/approve", Map.of("password", PWD)).andExpect(status().isOk()));
        assertThat(ok.get("versionNo").asInt()).isEqualTo(3);
        assertThat(ok.get("lines").get(1).get("limitText").asText()).isEqualTo("≥ 98 %");

        // 新版本生效，旧版本自动替代
        long v4 = body(call(qa, "/api/quality/specs", spec(m, "加严")).andExpect(status().isOk())).get("id").asLong();
        call(qso, "/api/quality/specs/" + v4 + "/approve", Map.of("password", PWD)).andExpect(status().isOk());
        assertThat(read(qa, "/api/quality/items/" + m + "/spec").get("versionNo").asInt()).isEqualTo(4);
        String all = read(qa, "/api/quality/specs").toString();
        assertThat(all).contains("SUPERSEDED");
        call(qso, "/api/quality/specs/" + v4 + "/approve", Map.of("password", PWD)).andExpect(status().isBadRequest());
    }

    @Test
    void materialsWithASpecNeedAPassingInspectionBeforeRelease() throws Exception {
        String admin = login("admin", "Admin@123456");
        String qa = login("qa1");
        String qso = login("qso1");
        long m = item(admin, "QS-RM2", "RM");
        long plain = item(admin, "QS-RM3", "RM");
        JsonNode spec = effectiveSpec(qa, admin, qso, m);

        // 没有标准的照旧
        Batch free = batch(plain, "QS-FREE-1", BatchStatus.QUARANTINE);
        call(qa, "/api/quality/batches/" + free.getId() + "/inspections", inspect(spec, "99", true, null)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("没有生效")));
        call(qa, "/api/quality/batches/" + free.getId() + "/material-decision", release("COA-1")).andExpect(status().isOk());

        // 有标准：没检验不能放
        Batch b = batch(m, "QS-B-1", BatchStatus.QUARANTINE);
        String base = "/api/quality/batches/" + b.getId();
        JsonNode panel = read(qa, base + "/inspections");
        assertThat(panel.get("blocker").asText()).contains("请先录入检验结果");
        call(qa, base + "/material-decision", release("COA-2")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("请先录入检验结果")));

        // 系统判定：含量 97.5 低于 98，人工填合格不算数；要写情况，生成偏差
        call(qa, base + "/inspections", inspect(spec, "97.5", true, null)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("写明情况")));
        call(login("purchase1"), base + "/inspections", inspect(spec, "99", true, null)).andExpect(status().isForbidden());
        Map<String, Object> future = inspect(spec, "99", true, null);
        future.put("inspectedDate", time.today().plusDays(1).toString());
        call(qa, base + "/inspections", future).andExpect(status().isBadRequest());
        panel = body(call(qa, base + "/inspections", inspect(spec, "97.5", true, "含量偏低")).andExpect(status().isOk()));
        JsonNode first = panel.get("inspections").get(0);
        assertThat(first.get("passed").asBoolean()).isFalse();
        assertThat(first.get("deviationNo").asText()).startsWith("DV");
        assertThat(panel.get("blocker").asText()).contains("不合格");
        call(qa, base + "/material-decision", release("COA-3")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不合格")));

        // 复检要写原因；复检合格也要等偏差关闭
        call(qa, base + "/inspections", inspect(spec, "99", true, null)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("复检")));
        call(qa, base + "/inspections", inspect(spec, "99", true, "按 SOP 复检")).andExpect(status().isOk());
        call(qa, base + "/material-decision", release("COA-4")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("偏差")));
        // 拒收不受限制
        call(qa, base + "/material-decision", Map.of("decision", "REJECTED", "reportNo", "COA-5", "note", "不合格", "password", PWD)).andExpect(status().isOk());
        // 判定后不能再录、不能作废
        call(qa, base + "/inspections", inspect(spec, "99", true, "x")).andExpect(status().isBadRequest());
        call(qso, base + "/inspections/" + first.get("id").asLong() + "/void", Map.of("note", "x")).andExpect(status().isBadRequest());

        // 录错了：QSO 作废后重录
        Batch c = batch(m, "QS-B-2", BatchStatus.QUARANTINE);
        String cb = "/api/quality/batches/" + c.getId();
        JsonNode wrong = body(call(qa, cb + "/inspections", inspect(spec, "99", false, "看错了")).andExpect(status().isOk()));
        long wrongId = wrong.get("inspections").get(0).get("id").asLong();
        call(qa, cb + "/inspections/" + wrongId + "/void", Map.of("note", "x")).andExpect(status().isForbidden());
        JsonNode voided = body(call(qso, cb + "/inspections/" + wrongId + "/void", Map.of("note", "录错了外观")).andExpect(status().isOk()));
        assertThat(voided.get("inspections").get(0).get("status").asText()).isEqualTo("VOID");
        // 别的批次的记录不能拿来作废
        call(qso, base + "/inspections/" + wrongId + "/void", Map.of("note", "x")).andExpect(status().isBadRequest());
        call(qa, cb + "/inspections", inspect(spec, "99.2", true, "作废后重录")).andExpect(status().isOk());
        // 作废的不合格记录生成的偏差还在，关了才能放；这里只验证拦截来自偏差而不是检验
        assertThat(read(qa, cb + "/inspections").get("blocker").isNull()).isTrue();
        call(qa, cb + "/material-decision", release("COA-6")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("偏差")));

        // 正常情况
        Batch d = batch(m, "QS-B-3", BatchStatus.QUARANTINE);
        String db = "/api/quality/batches/" + d.getId();
        JsonNode good = body(call(qa, db + "/inspections", inspect(spec, "99", true, null)).andExpect(status().isOk()));
        assertThat(good.get("blocker").isNull()).isTrue();
        assertThat(good.get("inspections").get(0).get("specVersion").asInt()).isEqualTo(1);
        call(qa, db + "/material-decision", release(good.get("inspections").get(0).get("inspectionNo").asText())).andExpect(status().isOk());
        assertThat(read(admin, "/api/golive").toString()).contains("质量标准");

        // 导出检验结果：每次检验每个项目一行，作废的、不合格的、偏差号都在；只读账号也能导；按检验日期过滤
        String sheet = sheetOf(mvc.perform(get("/api/reports/inspections").header("Authorization", "Bearer " + login("viewer1"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertThat(sheet).contains(first.get("inspectionNo").asText()).contains("QS-B-1").contains("97.5").contains("不合格").contains(first.get("deviationNo").asText())
                .contains("含量偏低").contains("已作废").contains("录错了外观").contains("QS-B-3").contains("99.2");
        String none = sheetOf(mvc.perform(get("/api/reports/inspections").param("from", time.today().plusDays(1).toString()).header("Authorization", "Bearer " + qa))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(none).doesNotContain("QS-B-1");
        assertThat(read(admin, "/api/audit?q=批次检验结果").toString()).contains("导出报表");
    }

    private static String sheetOf(byte[] xlsx) throws Exception {
        String sheet = "";
        try (var z = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(xlsx), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.getName().startsWith("xl/worksheets/")) {
                    sheet = new String(z.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        return sheet;
    }

    @Test
    void finishedGoodsWithASpecNeedAPassingInspectionForMarketRelease() throws Exception {
        String admin = login("admin", "Admin@123456");
        String qa = login("qa1");
        String qso = login("qso1");
        long f = item(admin, "QS-FG1", "FG");
        JsonNode spec = effectiveSpec(qa, admin, qso, f);
        Batch b = batch(f, "QS-FG-B1", BatchStatus.PENDING_RELEASE);
        String base = "/api/quality/batches/" + b.getId();
        Map<String, Object> rel = new HashMap<>(Map.of("decision", "RELEASED", "reportNo", "R-1", "note", "测试", "inspectionPassed", true, "recordsComplete", true,
                "consistentWithFiling", true, "retainQty", 2, "retainLocation", "留样室", "password", PWD));
        call(qso, base + "/market-release", rel).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("请先录入检验结果")));
        call(qa, base + "/inspections", inspect(spec, "99", true, null)).andExpect(status().isOk());
        call(qso, base + "/market-release", rel).andExpect(status().isOk());
    }
}
