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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.master.PartnerRepository;
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
 * 法规目录和产品合规资料。目录用的都是假物质，不是法规原文。
 * 用单独的库：这里建了没有配方的成品、导入过目录，留在共用库里会让上线检查的用例随执行顺序失败。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-compliance;MODE=MySQL;DB_CLOSE_DELAY=-1")
class ComplianceRegulatoryTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PartnerRepository partners;
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

    private ResultActions read(String t, String url) throws Exception {
        return mvc.perform(get(url).header("Authorization", "Bearer " + t));
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String text(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private long rm(String admin, String code, String inci, String limit) throws Exception {
        Map<String, Object> m = new HashMap<>(Map.of("code", code, "name", "原料" + code, "itemType", "RM", "unit", "kg", "banned", false, "active", true, "shelfMonths", 36));
        if (inci != null) {
            m.put("inci", inci);
        }
        if (limit != null) {
            m.put("limitPct", new BigDecimal(limit));
        }
        return body(call(admin, "/api/items", m).andExpect(status().isOk())).get("id").asLong();
    }

    private long fg(String admin, String code) throws Exception {
        return body(call(admin, "/api/items", Map.of("code", code, "name", "成品" + code, "itemType", "FG", "unit", "瓶", "banned", false, "active", true,
                "shelfMonths", 36, "netG", 100, "filingNo", "备案" + code)).andExpect(status().isOk())).get("id").asLong();
    }

    private ResultActions saveBom(String admin, long fgId, long itemId, String qty) throws Exception {
        return mvc.perform(put("/api/boms/" + fgId).header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("lines", List.of(Map.of("itemId", itemId, "qtyPer", new BigDecimal(qty), "supplyMode", "BRAND"))))));
    }

    private Map<String, Object> rule(String catalog, String inci, String max) {
        Map<String, Object> m = new HashMap<>(Map.of("catalog", catalog, "inciName", inci, "source", "测试数据 v1"));
        if (max != null) {
            m.put("maxPct", new BigDecimal(max));
        }
        return m;
    }

    private JsonNode importRules(String token, boolean dryRun, List<Map<String, Object>> rows) throws Exception {
        return body(call(token, "/api/regulatory/import", Map.of("dryRun", dryRun, "rows", rows)).andExpect(status().isOk()));
    }

    private long ruleId(String token, String inci) throws Exception {
        for (JsonNode s : body(read(token, "/api/regulatory"))) {
            if (inci.equals(s.get("inciName").asText())) {
                return s.get("id").asLong();
            }
        }
        throw new AssertionError(inci);
    }

    @Test
    void importIsAllOrNothingNeedsASourceAndOnlyQualityCanDoIt() throws Exception {
        String qa = login("qa1");
        call(login("purchase1"), "/api/regulatory/import", Map.of("dryRun", true, "rows", List.of(rule("BANNED", "Fake A", null)))).andExpect(status().isForbidden());
        call(login("admin", "Admin@123456"), "/api/regulatory/import", Map.of("dryRun", true, "rows", List.of(rule("BANNED", "Fake A", null)))).andExpect(status().isForbidden());

        // 预检不落库
        JsonNode dry = importRules(qa, true, List.of(rule("BANNED", "Fake Dry", null)));
        assertThat(dry.get("created").asInt()).isEqualTo(1);
        assertThat(text(read(qa, "/api/regulatory"))).doesNotContain("Fake Dry");

        // 有错就全不成，按 Excel 行号报
        Map<String, Object> noSource = new HashMap<>(rule("BANNED", "Fake B", null));
        noSource.put("source", " ");
        JsonNode bad = body(call(qa, "/api/regulatory/import", Map.of("dryRun", false, "rows", List.of(rule("BANNED", "Fake OK", null), noSource, rule("BANNED", "Fake C", "1"), rule("RESTRICTED", "fake ok", "1")))).andExpect(status().isOk()));
        assertThat(bad.get("applied").asBoolean()).isFalse();
        assertThat(bad.get("errors").size()).isEqualTo(3);
        assertThat(bad.get("errors").get(0).get("line").asInt()).isEqualTo(3);
        assertThat(bad.toString()).contains("来源").contains("禁用的不能填浓度上限").contains("重复");
        assertThat(text(read(qa, "/api/regulatory"))).doesNotContain("Fake OK");

        // 修改已有的，审计记下改了什么
        importRules(qa, false, List.of(rule("RESTRICTED", "Fake Limit", "2")));
        assertThat(importRules(qa, false, List.of(rule("RESTRICTED", "Fake Limit", "1.5"))).get("updated").asInt()).isEqualTo(1);
        assertThat(text(read(qa, "/api/audit?q=修改法规条目"))).contains("Fake Limit").contains("2%").contains("1.5%");
        call(qa, "/api/regulatory/" + ruleId(qa, "Fake Limit") + "/retire", Map.of("reason", "测试")).andExpect(status().isOk());
    }

    @Test
    void importPreviewShowsRuleChangesAndAccentsAreTheSameSubstance() throws Exception {
        String qa = login("qa1");
        // MySQL 的排序规则认为 é 和 e 相同，这里要和它一致
        JsonNode dup = importRules(qa, true, List.of(rule("RESTRICTED", "Fake Cafe", "1"), rule("BANNED", "Fake Café", null)));
        assertThat(dup.get("errors").get(0).get("line").asInt()).isEqualTo(3);
        assertThat(dup.get("errors").get(0).get("message").asText()).contains("重复");

        importRules(qa, false, List.of(rule("BANNED", "Fake Café", null), rule("RESTRICTED", "Fake Loose", "1")));

        // 预检要让人看到放宽：禁用变限用、留空上限等于无上限。此时还没改
        JsonNode dry = importRules(qa, true, List.of(rule("RESTRICTED", "Fake Cafe", "5"), rule("RESTRICTED", "Fake Loose", null)));
        assertThat(dry.get("updated").asInt()).isEqualTo(2);
        assertThat(dry.get("changes").toString()).contains("禁用").contains("限用").contains("无上限");
        assertThat(dry.get("changes").size()).isEqualTo(3);
        assertThat(text(read(qa, "/api/regulatory"))).contains("Fake Café").doesNotContain("\"Fake Cafe\"");

        // 来源换版本不算规则变化
        Map<String, Object> newSource = new HashMap<>(rule("RESTRICTED", "Fake Loose", "1"));
        newSource.put("source", "测试数据 v2");
        assertThat(importRules(qa, true, List.of(newSource)).get("changes").size()).isZero();

        // 停用的重新导入回来，也要列出来
        call(qa, "/api/regulatory/" + ruleId(qa, "Fake Loose") + "/retire", Map.of("reason", "测试")).andExpect(status().isOk());
        assertThat(importRules(qa, true, List.of(rule("RESTRICTED", "Fake Loose", "1"))).get("changes").toString()).contains("已停用");

        // 真正导入：每条变化单独记审计
        importRules(qa, false, List.of(rule("RESTRICTED", "Fake Cafe", "5")));
        assertThat(text(read(qa, "/api/audit?q=修改法规条目"))).contains("Fake Cafe").contains("类别 禁用 改为 限用");
        call(qa, "/api/regulatory/" + ruleId(qa, "Fake Cafe") + "/retire", Map.of("reason", "测试")).andExpect(status().isOk());
    }

    @Test
    void catalogIsEnforcedInFormulasPurchasesAndRfqsWhicheverIsStricter() throws Exception {
        String admin = login("admin", "Admin@123456");
        String qa = login("qa1");
        String buyer = login("purchase1");
        // 物料上没标禁用，INCI 大小写和空格不同也要对上
        long banned = rm(admin, "RG-BAN", "  fake BANNED one ", null);
        long limited = rm(admin, "RG-LIM", "Fake Limited One", "5");
        long free = rm(admin, "RG-FREE", "Fake Unlisted", null);
        long f1 = fg(admin, "RG-FG1");
        long sup = partners.findByCode("SUP-HC").orElseThrow().getId();
        importRules(qa, false, List.of(rule("BANNED", "Fake Banned One", null), rule("RESTRICTED", "Fake Limited One", "1")));
        try {
            saveBom(admin, f1, banned, "0.001").andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("禁用")));
            call(buyer, "/api/purchase-orders", Map.of("supplierId", sup, "lines", List.of(Map.of("itemId", banned, "qty", 1)))).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("禁用")));
            call(buyer, "/api/rfqs", Map.of("title", "x", "deadline", time.today().plusDays(1).toString(), "supplierIds", List.of(sup), "lines", List.of(Map.of("itemId", banned, "qty", 1))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("禁用")));
            // 净含量 100g：2g = 2%。物料写的上限 5%，目录 1%，按目录
            saveBom(admin, f1, limited, "0.002").andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("超出限量 1%")));
            saveBom(admin, f1, limited, "0.001").andExpect(status().isOk());
            // 目录里没有的不受影响
            call(buyer, "/api/purchase-orders", Map.of("supplierId", sup, "lines", List.of(Map.of("itemId", free, "qty", 1)))).andExpect(status().isOk());

            // 对照：列出和目录不一致的物料
            JsonNode cmp = body(read(qa, "/api/regulatory/comparison").andExpect(status().isOk()));
            assertThat(cmp.toString()).contains("BANNED_NOT_MARKED").contains("LIMIT_LOOSER");
        } finally {
            call(qa, "/api/regulatory/" + ruleId(qa, "Fake Banned One") + "/retire", Map.of("reason", "测试结束")).andExpect(status().isOk());
            call(qa, "/api/regulatory/" + ruleId(qa, "Fake Limited One") + "/retire", Map.of("reason", "测试结束")).andExpect(status().isOk());
        }
        // 停用后不再拦
        saveBom(admin, fg(admin, "RG-FG2"), banned, "0.001").andExpect(status().isOk());
    }

    /** 法规一变，已上市的产品就可能不合规，要有人看到。 */
    @Test
    void newRulesFlagFormulasAlreadyInEffect() throws Exception {
        String admin = login("admin", "Admin@123456");
        String qa = login("qa1");
        long m = rm(admin, "RG-VIO", "Fake Later Banned", null);
        long f = fg(admin, "RG-FG3");
        saveBom(admin, f, m, "0.001").andExpect(status().isOk());
        assertThat(body(read(qa, "/api/dashboard")).get("todos").toString()).doesNotContain("RG-FG3");
        importRules(qa, false, List.of(rule("BANNED", "Fake Later Banned", null)));
        try {
            JsonNode cmp = body(read(qa, "/api/regulatory/comparison"));
            assertThat(cmp.get("violations").toString()).contains("RG-FG3");
            String dash = body(read(qa, "/api/dashboard")).get("todos").toString();
            assertThat(dash).contains("配方不符合法规目录").contains("RG-FG3");
            assertThat(text(read(admin, "/api/golive"))).contains("配方和法规");
        } finally {
            call(qa, "/api/regulatory/" + ruleId(qa, "Fake Later Banned") + "/retire", Map.of("reason", "测试结束")).andExpect(status().isOk());
        }
        assertThat(body(read(qa, "/api/dashboard")).get("todos").toString()).doesNotContain("RG-FG3");
    }

    private Map<String, Object> doc(long fg, String type) {
        return new HashMap<>(Map.of("fgItemId", fg, "docType", type, "title", type + " 报告", "evaluator", "第三方机构", "docDate", time.today().toString()));
    }

    private JsonNode statusOf(String token, long fg) throws Exception {
        for (JsonNode p : body(read(token, "/api/compliance/products"))) {
            if (p.get("fgItemId").asLong() == fg) {
                return p;
            }
        }
        throw new AssertionError(fg);
    }

    @Test
    void docsNeedSignoffAndGoStaleWhenTheFormulaChanges() throws Exception {
        String admin = login("admin", "Admin@123456");
        String qa = login("qa1");
        String qso = login("qso1");
        long m = rm(admin, "RG-DOC", "Fake Doc Ingredient", null);
        long f = fg(admin, "RG-FG4");

        // 没有配方不能登记
        call(qa, "/api/compliance/docs", doc(f, "SAFETY")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("还没有生效配方")));
        saveBom(admin, f, m, "0.01").andExpect(status().isOk());
        assertThat(statusOf(qa, f).get("safety").asText()).isEqualTo("MISSING");

        // 校验：功效要写宣称、日期不能晚于今天、岗位
        call(qa, "/api/compliance/docs", doc(f, "EFFICACY")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("宣称")));
        Map<String, Object> future = doc(f, "SAFETY");
        future.put("docDate", time.today().plusDays(1).toString());
        call(qa, "/api/compliance/docs", future).andExpect(status().isBadRequest());
        call(login("sales1"), "/api/compliance/docs", doc(f, "SAFETY")).andExpect(status().isForbidden());

        long safety = body(call(qa, "/api/compliance/docs", doc(f, "SAFETY")).andExpect(status().isOk())).get("id").asLong();
        assertThat(statusOf(qa, f).get("safety").asText()).isEqualTo("PENDING");
        // 只有 QSO 能签，要密码；登记人不能签自己的
        call(qa, "/api/compliance/docs/" + safety + "/approve", Map.of("password", PWD)).andExpect(status().isForbidden());
        call(qso, "/api/compliance/docs/" + safety + "/approve", Map.of("password", "wrong")).andExpect(status().isBadRequest());
        long own = body(call(qso, "/api/compliance/docs", doc(f, "LABEL")).andExpect(status().isOk())).get("id").asLong();
        call(qso, "/api/compliance/docs/" + own + "/approve", Map.of("password", PWD)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("登记人")));
        call(qso, "/api/compliance/docs/" + own + "/reject", Map.of("note", "标签有误")).andExpect(status().isOk());
        assertThat(statusOf(qa, f).get("label").asText()).isEqualTo("MISSING");

        call(qso, "/api/compliance/docs/" + safety + "/approve", Map.of("password", PWD)).andExpect(status().isOk());
        long label = body(call(qa, "/api/compliance/docs", doc(f, "LABEL")).andExpect(status().isOk())).get("id").asLong();
        call(qso, "/api/compliance/docs/" + label + "/approve", Map.of("password", PWD)).andExpect(status().isOk());
        JsonNode st = statusOf(qa, f);
        assertThat(st.get("safety").asText()).isEqualTo("OK");
        assertThat(st.get("label").asText()).isEqualTo("OK");
        assertThat(st.get("efficacy").asText()).isEqualTo("MISSING");
        assertThat(st.get("versionNo").asInt()).isEqualTo(1);

        // 配方变成 V2：V1 的资料要重新评估，总览提醒
        long draft = body(call(admin, "/api/plm/formulas/" + f + "/draft", Map.of("changeNote", "调整用量", "lines", List.of(Map.of("itemId", m, "qtyPer", new BigDecimal("0.02"), "supplyMode", "BRAND")))).andExpect(status().isOk())).get("id").asLong();
        call(admin, "/api/plm/versions/" + draft + "/submit", Map.of()).andExpect(status().isOk());
        // 可以先为待签批的新版本做评估
        Map<String, Object> early = doc(f, "SAFETY");
        early.put("formulaVersionId", draft);
        long earlyDoc = body(call(qa, "/api/compliance/docs", early).andExpect(status().isOk())).get("id").asLong();
        assertThat(statusOf(qa, f).get("safety").asText()).isEqualTo("OK");
        // 配方变了要先完成备案
        long filing = body(call(qa, "/api/plm/filings", Map.of("fgItemId", f, "filingType", "GENERAL", "formulaVersionId", draft, "submittedDate", time.today().toString())).andExpect(status().isOk())).get("id").asLong();
        call(qa, "/api/plm/filings/" + filing + "/complete", Map.of("filingNo", "备案RG-FG4-V2", "filedDate", time.today().toString())).andExpect(status().isOk());
        call(qso, "/api/plm/versions/" + draft + "/approve", Map.of("password", PWD)).andExpect(status().isOk());
        st = statusOf(qa, f);
        assertThat(st.get("versionNo").asInt()).isEqualTo(2);
        assertThat(st.get("safety").asText()).isEqualTo("PENDING");
        assertThat(st.get("label").asText()).isEqualTo("STALE");
        assertThat(text(read(qa, "/api/dashboard"))).contains("合规资料要重新评估").contains("RG-FG4");
        call(qso, "/api/compliance/docs/" + earlyDoc + "/approve", Map.of("password", PWD)).andExpect(status().isOk());
        assertThat(statusOf(qa, f).get("safety").asText()).isEqualTo("OK");

        // 作废后要重新做；不能再作废
        call(qa, "/api/compliance/docs/" + earlyDoc + "/void", Map.of("note", "x")).andExpect(status().isForbidden());
        call(qso, "/api/compliance/docs/" + earlyDoc + "/void", Map.of("note", "报告撤回")).andExpect(status().isOk());
        call(qso, "/api/compliance/docs/" + earlyDoc + "/void", Map.of("note", "x")).andExpect(status().isBadRequest());
        assertThat(statusOf(qa, f).get("safety").asText()).isEqualTo("STALE");
    }

    /** 手算：净含量 100g。A 5g = 5%，B 0.05g = 0.05%，C 1g = 1%。包材不进成分表。 */
    @Test
    void ingredientDraftIsSortedByContentAndFlagsProblems() throws Exception {
        String admin = login("admin", "Admin@123456");
        long a = rm(admin, "RG-INA", "Fake A", null);
        long b = rm(admin, "RG-INB", null, null);
        long c = rm(admin, "RG-INC", "Fake C", null);
        long pk = body(call(admin, "/api/items", Map.of("code", "RG-PK", "name", "瓶子", "itemType", "PK", "unit", "个", "banned", false, "active", true)).andExpect(status().isOk())).get("id").asLong();
        long f = fg(admin, "RG-FG5");
        mvc.perform(put("/api/boms/" + f).header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("lines", List.of(
                Map.of("itemId", b, "qtyPer", new BigDecimal("0.00005"), "supplyMode", "BRAND"),
                Map.of("itemId", a, "qtyPer", new BigDecimal("0.005"), "supplyMode", "BRAND"),
                Map.of("itemId", pk, "qtyPer", 1, "supplyMode", "BRAND"),
                Map.of("itemId", c, "qtyPer", new BigDecimal("0.001"), "supplyMode", "BRAND")))))).andExpect(status().isOk());
        JsonNode list = body(read(login("qa1"), "/api/compliance/products/" + f + "/ingredients").andExpect(status().isOk()));
        assertThat(list.get("lines").size()).isEqualTo(3);
        assertThat(list.get("lines").get(0).get("itemCode").asText()).isEqualTo("RG-INA");
        assertThat(list.get("lines").get(0).get("pct").decimalValue()).isEqualByComparingTo("5");
        assertThat(list.get("lines").get(1).get("itemCode").asText()).isEqualTo("RG-INC");
        assertThat(list.get("lines").get(2).get("pct").decimalValue()).isEqualByComparingTo("0.05");
        assertThat(list.get("text").asText()).contains("（≤0.1%）原料RG-INB");
        assertThat(list.get("problems").toString()).contains("原料RG-INB 没填 INCI").contains("复配原料").contains("合计 6.05%");
        // 没配方
        read(login("qa1"), "/api/compliance/products/" + fg(admin, "RG-FG6") + "/ingredients").andExpect(status().isBadRequest());
    }

    @Test
    void goLiveChecklistMentionsTheCatalogAndDocs() throws Exception {
        String list = text(read(login("admin", "Admin@123456"), "/api/golive").andExpect(status().isOk()));
        assertThat(list).contains("法规目录").contains("产品合规资料");
    }
}
