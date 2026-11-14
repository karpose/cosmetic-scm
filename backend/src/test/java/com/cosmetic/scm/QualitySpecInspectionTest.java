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
import org.springframework.mock.web.MockMultipartFile;
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

    // ---------- 采用供方报告、取样、导出 ----------

    private Map<String, Object> partner(String code, boolean qualified) {
        Map<String, Object> m = new HashMap<>(Map.of("code", code, "name", "供方" + code, "kind", "SUPPLIER", "licenseNo", "LIC-" + code,
                "licenseExpiry", time.today().plusYears(1).toString(), "qualified", qualified, "criticalSupplier", false));
        return m;
    }

    private Batch received(long itemId, long supplierId, String no, String poNo) {
        Batch b = batch(itemId, no, BatchStatus.QUARANTINE);
        b.setSourceType(SourceType.PO);
        b.setSourceNo(poNo);
        b.setPartnerId(supplierId);
        b.setExtBatchNo("EXT-" + no);
        return batches.save(b);
    }

    private void upload(String t, String ownerType, long id) throws Exception {
        mvc.perform(multipart("/api/attachments").file(new MockMultipartFile("file", "coa.pdf", "application/pdf", "%PDF-1.4 coa".getBytes(StandardCharsets.UTF_8)))
                .param("ownerType", ownerType).param("ownerId", String.valueOf(id)).header("Authorization", "Bearer " + t)).andExpect(status().isOk());
    }

    /** 外观自检；含量按 COA 或自检。 */
    private Map<String, Object> results(JsonNode spec, String content, boolean contentFromCoa, String sampleNote, String coaNo) {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(Map.of("lineId", spec.get("lines").get(0).get("id").asLong(), "text", "正常", "passed", true));
        rows.add(Map.of("lineId", spec.get("lines").get(1).get("id").asLong(), "value", new BigDecimal(content), "source", contentFromCoa ? "COA" : "TEST"));
        Map<String, Object> m = new HashMap<>(Map.of("inspectedDate", time.today().toString(), "results", rows));
        if (sampleNote != null) {
            m.put("sampleNote", sampleNote);
        }
        if (coaNo != null) {
            m.put("coaNo", coaNo);
        }
        return m;
    }

    @Test
    void supplierReportsCanReplaceSelfTestsOnlyWithinTheRules() throws Exception {
        String admin = login("admin", "Admin@123456");
        String qa = login("qa1");
        String qso = login("qso1");
        long m = item(admin, "QS-RM5", "RM");
        JsonNode sup = body(call(admin, "/api/partners", partner("QS-SUP1", true)).andExpect(status().isOk()));
        long s1 = sup.get("id").asLong();
        String supName = sup.get("name").asText();

        // 没有可采用供方报告的项目，不能设连续批数；批数至少 1
        Map<String, Object> lineLook = Map.of("name", "外观", "criteria", "测试");
        Map<String, Object> lineContent = Map.of("name", "含量", "unit", "%", "minValue", new BigDecimal("98"), "coaAllowed", true);
        call(qa, "/api/quality/specs", Map.of("itemId", m, "changeNote", "x", "lines", List.of(lineLook), "coaMaxStreak", 2))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("没有可采用供方报告的项目")));
        call(qa, "/api/quality/specs", Map.of("itemId", m, "changeNote", "x", "lines", List.of(lineLook, lineContent), "coaMaxStreak", 0))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("至少是 1")));
        long specId = body(call(qa, "/api/quality/specs", Map.of("itemId", m, "changeNote", "首版", "lines", List.of(lineLook, lineContent),
                "samplingPlan", "每批取 3 桶", "coaMaxStreak", 2)).andExpect(status().isOk())).get("id").asLong();
        JsonNode spec = body(call(qso, "/api/quality/specs/" + specId + "/approve", Map.of("password", PWD)).andExpect(status().isOk()));
        assertThat(spec.get("samplingPlan").asText()).isEqualTo("每批取 3 桶");
        assertThat(spec.get("coaMaxStreak").asInt()).isEqualTo(2);
        assertThat(spec.get("lines").get(0).get("coaAllowed").asBoolean()).isFalse();
        assertThat(spec.get("lines").get(1).get("coaAllowed").asBoolean()).isTrue();

        // 第 1 批：这家供方还没有全项自检，不能采用报告；自检要写取样情况
        Batch b1 = received(m, s1, "QS-C-1", "PO-NONE-1");
        String u1 = "/api/quality/batches/" + b1.getId() + "/inspections";
        JsonNode p1 = read(qa, u1);
        assertThat(p1.get("coaBlocker").asText()).contains("还没有全项自检");
        assertThat(p1.get("supplierName").asText()).isEqualTo(supName);
        upload(qa, "BATCH", b1.getId());
        call(qa, u1, results(spec, "99", true, "3 桶各 50 g", "COA-1")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("全项自检")));
        call(qa, u1, results(spec, "99", false, null, null)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("取样情况")));
        JsonNode full = body(call(qa, u1, results(spec, "99", false, "3 桶各 50 g", null)).andExpect(status().isOk()));
        assertThat(full.get("inspections").get(0).get("sampleNote").asText()).isEqualTo("3 桶各 50 g");
        assertThat(full.get("inspections").get(0).get("coaNo").isNull()).isTrue();
        assertThat(full.get("inspections").get(0).get("results").get(1).get("source").asText()).isEqualTo("TEST");

        // 第 2 批：可以采用。标准里要自检的项目不能采用；要报告编号和报告扫描件
        Batch b2 = received(m, s1, "QS-C-2", "PO-NONE-2");
        String u2 = "/api/quality/batches/" + b2.getId() + "/inspections";
        assertThat(read(qa, u2).get("coaBlocker").isNull()).isTrue();
        Map<String, Object> lookFromCoa = results(spec, "99", true, "3 桶各 50 g", "COA-2");
        lookFromCoa.put("results", List.of(Map.of("lineId", spec.get("lines").get(0).get("id").asLong(), "text", "正常", "passed", true, "source", "COA"),
                Map.of("lineId", spec.get("lines").get(1).get("id").asLong(), "value", 99, "source", "COA")));
        call(qa, u2, lookFromCoa).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("要每批自检")));
        call(qa, u2, results(spec, "99", true, "3 桶各 50 g", null)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("报告编号")));
        call(qa, u2, results(spec, "99", true, "3 桶各 50 g", "COA-2")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("上传供方检验报告")));
        upload(qa, "BATCH", b2.getId());
        JsonNode adopted = body(call(qa, u2, results(spec, "99", true, "3 桶各 50 g", "COA-2")).andExpect(status().isOk()));
        assertThat(adopted.get("inspections").get(0).get("coaNo").asText()).isEqualTo("COA-2");
        assertThat(adopted.get("inspections").get(0).get("results").get(0).get("source").asText()).isEqualTo("TEST");
        assertThat(adopted.get("inspections").get(0).get("results").get(1).get("source").asText()).isEqualTo("COA");
        assertThat(adopted.get("blocker").isNull()).isTrue();
        call(qa, "/api/quality/batches/" + b2.getId() + "/material-decision", release("COA-2")).andExpect(status().isOk());

        // 第 3 批：报告扫描件在采购单上也算（供应商在门户里传到采购单）；报告上的数值照样按限度判定
        JsonNode po = read(admin, "/api/purchase-orders").get(0);
        Batch b3 = received(m, s1, "QS-C-3", po.get("poNo").asText());
        String u3 = "/api/quality/batches/" + b3.getId() + "/inspections";
        call(qa, u3, results(spec, "99", true, "3 桶各 50 g", "COA-3")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("上传供方检验报告")));
        upload(qa, "PURCHASE_ORDER", po.get("id").asLong());
        call(qa, u3, results(spec, "97", true, "3 桶各 50 g", "COA-3")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("写明情况")));
        JsonNode third = body(call(qa, u3, results(spec, "99", true, "3 桶各 50 g", "COA-3")).andExpect(status().isOk()));
        long thirdId = third.get("inspections").get(0).get("id").asLong();

        // 第 4 批：已经连续 2 批采用，这批要全项自检。作废第 3 批的记录后又可以采用，重录后又不行
        Batch b4 = received(m, s1, "QS-C-4", "PO-NONE-4");
        String u4 = "/api/quality/batches/" + b4.getId() + "/inspections";
        upload(qa, "BATCH", b4.getId());
        assertThat(read(qa, u4).get("coaBlocker").asText()).contains("已连续 2 批").contains("最多 2 批");
        call(qa, u4, results(spec, "99", true, "3 桶各 50 g", "COA-4")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("已连续 2 批")));
        call(qso, u3 + "/" + thirdId + "/void", Map.of("note", "录错了报告编号")).andExpect(status().isOk());
        assertThat(read(qa, u4).get("coaBlocker").isNull()).isTrue();
        call(qa, u3, results(spec, "99", true, "3 桶各 50 g", "COA-3A")).andExpect(status().isOk());
        assertThat(read(qa, u4).get("coaBlocker").asText()).contains("已连续 2 批");
        // 同一批再录一次不算新的一批
        call(qa, u3, results(spec, "99.1", true, "3 桶各 50 g", "COA-3B")).andExpect(status().isOk());
        call(qa, u4, results(spec, "99", false, "3 桶各 50 g", null)).andExpect(status().isOk());

        // 第 5 批：全项自检合格后重新计数。别的供方各算各的
        Batch b5 = received(m, s1, "QS-C-5", "PO-NONE-5");
        String u5 = "/api/quality/batches/" + b5.getId() + "/inspections";
        assertThat(read(qa, u5).get("coaBlocker").isNull()).isTrue();
        long s2 = body(call(admin, "/api/partners", partner("QS-SUP2", true)).andExpect(status().isOk())).get("id").asLong();
        Batch other = received(m, s2, "QS-C-OTHER", "PO-NONE-6");
        assertThat(read(qa, "/api/quality/batches/" + other.getId() + "/inspections").get("coaBlocker").asText()).contains("还没有全项自检");

        // 全项自检不合格：下一批不能采用
        call(qa, u5, results(spec, "97", false, "3 桶各 50 g", null)).andExpect(status().isBadRequest());
        Map<String, Object> failed = results(spec, "97", false, "3 桶各 50 g", null);
        failed.put("note", "含量偏低");
        call(qa, u5, failed).andExpect(status().isOk());
        Batch b6 = received(m, s1, "QS-C-6", "PO-NONE-7");
        String u6 = "/api/quality/batches/" + b6.getId() + "/inspections";
        assertThat(read(qa, u6).get("coaBlocker").asText()).contains("不合格").contains("全项自检");
        call(qa, u6, results(spec, "99", false, "3 桶各 50 g", null)).andExpect(status().isOk());

        // 供方被移出合格名录：不能采用它的报告
        Batch b7 = received(m, s1, "QS-C-7", "PO-NONE-8");
        String u7 = "/api/quality/batches/" + b7.getId() + "/inspections";
        assertThat(read(qa, u7).get("coaBlocker").isNull()).isTrue();
        mvc.perform(put("/api/partners/" + s1).header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(partner("QS-SUP1", false)))).andExpect(status().isOk());
        assertThat(read(qa, u7).get("coaBlocker").asText()).contains("不在合格名录");
        upload(qa, "BATCH", b7.getId());
        call(qa, u7, results(spec, "99", true, "3 桶各 50 g", "COA-7")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不在合格名录")));

        // 期初导入的批次没有供方
        Batch open = batch(m, "QS-C-OPEN", BatchStatus.QUARANTINE);
        assertThat(read(qa, "/api/quality/batches/" + open.getId() + "/inspections").get("coaBlocker").asText()).contains("期初导入");

        // 审计写明采用了几项、报告编号
        assertThat(read(admin, "/api/audit?q=QS-C-2").toString()).contains("1 项采用供方报告 COA-2");

        // 导出：全部记录，一个项目一行，只读账号也能导
        byte[] xlsx = mvc.perform(get("/api/reports/inspections").header("Authorization", "Bearer " + login("viewer1"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        String sheet = sheetXml(xlsx);
        assertThat(sheet).contains("QS-C-2").contains("COA-2").contains("供方报告").contains("自检").contains("3 桶各 50 g").contains("已作废").contains("录错了报告编号").contains(supName);
    }

    @Test
    void specsWithoutAStreakLimitLetEveryBatchUseTheSupplierReport() throws Exception {
        String admin = login("admin", "Admin@123456");
        String qa = login("qa1");
        String qso = login("qso1");
        long m = item(admin, "QS-RM6", "RM");
        long s1 = body(call(admin, "/api/partners", partner("QS-SUP3", true)).andExpect(status().isOk())).get("id").asLong();
        // 两个项目都可以采用，没有取样要求、不限连续批数
        long specId = body(call(qa, "/api/quality/specs", Map.of("itemId", m, "changeNote", "首版", "lines", List.of(Map.of("name", "外观", "criteria", "测试", "coaAllowed", true),
                Map.of("name", "含量", "unit", "%", "minValue", new BigDecimal("98"), "coaAllowed", true)))).andExpect(status().isOk())).get("id").asLong();
        JsonNode spec = body(call(qso, "/api/quality/specs/" + specId + "/approve", Map.of("password", PWD)).andExpect(status().isOk()));
        Batch b = received(m, s1, "QS-D-1", "PO-NONE-9");
        String u = "/api/quality/batches/" + b.getId() + "/inspections";
        assertThat(read(qa, u).get("coaBlocker").isNull()).isTrue();
        upload(qa, "BATCH", b.getId());
        Map<String, Object> all = new HashMap<>(Map.of("inspectedDate", time.today().toString(), "coaNo", "COA-D1", "results", List.of(
                Map.of("lineId", spec.get("lines").get(0).get("id").asLong(), "text", "正常", "passed", true, "source", "COA"),
                Map.of("lineId", spec.get("lines").get(1).get("id").asLong(), "value", 99, "source", "COA"))));
        call(qa, u, all).andExpect(status().isOk());
        // 没有标 source 的旧请求按自检算
        Batch c = received(m, s1, "QS-D-2", "PO-NONE-10");
        JsonNode old = body(call(qa, "/api/quality/batches/" + c.getId() + "/inspections", inspect(spec, "99", true, null)).andExpect(status().isOk()));
        assertThat(old.get("inspections").get(0).get("results").get(0).get("source").asText()).isEqualTo("TEST");
        assertThat(old.get("inspections").get(0).get("coaNo").isNull()).isTrue();
    }

    private static String sheetXml(byte[] xlsx) throws Exception {
        try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(xlsx), StandardCharsets.UTF_8)) {
            for (java.util.zip.ZipEntry e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                if (e.getName().startsWith("xl/worksheets/")) {
                    return new String(z.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new IllegalStateException("没有工作表");
    }
}
