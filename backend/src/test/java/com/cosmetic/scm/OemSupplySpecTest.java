package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.cosmetic.scm.audit.AuditRepository;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.demo.DemoDataLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 委托方提供物料验收标准（检查要点委托生产版第 12 项）：品牌供料的现行质量标准交给受托厂，受托厂在门户逐版确认，
 * 或由质量部门登记线下交接；换版后要重新确认。会修订示例标准，用单独的库。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-oem-supply;MODE=MySQL;DB_CLOSE_DELAY=-1")
class OemSupplySpecTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;
    private static final String PORTAL = "/api/portal/oem/material-specs";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TimeProvider time;
    @Autowired AuditRepository audits;

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

    private JsonNode read(String t, String url) throws Exception {
        return ok(mvc.perform(get(url).header("Authorization", "Bearer " + t)));
    }

    private JsonNode row(JsonNode rows, String itemCode) {
        for (JsonNode r : rows) {
            if (itemCode.equals(r.get("itemCode").asText())) {
                return r;
            }
        }
        throw new AssertionError("没有 " + itemCode + "：" + rows);
    }

    private long itemId(String t, String code) throws Exception {
        for (JsonNode n : read(t, "/api/items")) {
            if (code.equals(n.get("code").asText())) {
                return n.get("id").asLong();
            }
        }
        throw new AssertionError(code);
    }

    private long partnerId(String t, String code) throws Exception {
        for (JsonNode n : read(t, "/api/partners")) {
            if (code.equals(n.get("code").asText())) {
                return n.get("id").asLong();
            }
        }
        throw new AssertionError(code);
    }

    private Long checkId;

    /** 同一时间只能有一份进行中的自查：建一次，之后每次重新读核对结果。 */
    private Map<Integer, JsonNode> evidence(String qa) throws Exception {
        LocalDate today = time.today();
        if (checkId == null) {
            checkId = ok(call(qa, "/api/qms/self-checks", Map.of("periodFrom", today.minusDays(30).toString(), "periodTo", today.toString())))
                    .get("check").get("id").asLong();
        }
        Map<Integer, JsonNode> byNo = new HashMap<>();
        read(qa, "/api/qms/self-checks/" + checkId + "/evidence").forEach(e -> byNo.put(e.get("itemNo").asInt(), e));
        return byNo;
    }

    @Autowired com.cosmetic.scm.production.ProductionService production;
    @Autowired com.cosmetic.scm.oemsupply.OemSupplyService oemSupply;

    private void requireAck(boolean on) {
        org.springframework.test.util.ReflectionTestUtils.setField((Object) org.springframework.test.util.AopTestUtils.getTargetObject(production), "requireSpecAck", on);
        org.springframework.test.util.ReflectionTestUtils.setField((Object) org.springframework.test.util.AopTestUtils.getTargetObject(oemSupply), "requireAckForIssue", on);
    }

    private long auditCount(String action) {
        return audits.findAllByOrderByIdDesc(PageRequest.of(0, 500)).stream().filter(a -> action.equals(a.getAction())).count();
    }

    @Test
    void factoryConfirmsSuppliedMaterialSpecsVersionByVersion() throws Exception {
        String qa = login("qa1", PWD);
        String qso = login("qso1", PWD);
        String oem = login("oem1", PWD);
        String buyer = login("purchase1", PWD);
        long a3 = partnerId(qa, "OEM-A3");

        // 示例：禾川在门户确认了精华瓶，彩盒还没确认；烟酰胺没有标准
        JsonNode ov = read(qa, "/api/oem-supply");
        JsonNode btl = row(ov.get("rows"), "PK-BTL-001");
        assertThat(btl.get("state").asText()).isEqualTo("ACKED");
        assertThat(btl.get("lastAck").get("method").asText()).isEqualTo("PORTAL");
        assertThat(row(ov.get("rows"), "PK-BOX-002").get("state").asText()).isEqualTo("UNACKED");
        assertThat(row(ov.get("rows"), "RM-NIA-001").get("state").asText()).isEqualTo("NO_SPEC");
        assertThat(row(ov.get("rows"), "RM-NIA-001").get("inci").asText()).isEqualTo("Niacinamide");
        // 受托厂代采的甘油、苯氧乙醇不在清单里
        assertThat(ov.get("rows").toString()).doesNotContain("RM-GLY-002").doesNotContain("RM-PHE-004");
        String todos = read(qso, "/api/dashboard").get("todos").toString();
        assertThat(todos).contains("受托厂没确认物料验收标准").contains("精华彩盒 v2");
        Map<Integer, JsonNode> ev = evidence(qa);
        assertThat(ev.get(12).get("level").asText()).isEqualTo("WARN");
        assertThat(ev.get(12).get("details").toString()).contains("精华彩盒 v2 第 1 版验收标准没有确认收到").contains("烟酰胺：品牌供料");

        // 门户：只有本厂要用的物料，有标准内容，没有内部的“可采用供方报告”
        JsonNode mine = read(oem, PORTAL);
        JsonNode box = row(mine, "PK-BOX-002");
        assertThat(box.get("lines").get(0).get("name").asText()).isEqualTo("印刷内容");
        assertThat(box.toString()).doesNotContain("coaAllowed");
        assertThat(row(mine, "PK-BTL-001").get("samplingPlan").asText()).contains("20 件");
        long boxSpec = box.get("specId").asLong();
        JsonNode after = ok(call(oem, PORTAL + "/" + boxSpec + "/ack", Map.of("note", "已收到")));
        assertThat(row(after, "PK-BOX-002").get("state").asText()).isEqualTo("ACKED");
        assertThat(row(after, "PK-BOX-002").get("ackedBy").asText()).contains("禾川");
        long acks = auditCount("受托厂确认物料验收标准");
        ok(call(oem, PORTAL + "/" + boxSpec + "/ack", Map.of()));
        assertThat(auditCount("受托厂确认物料验收标准")).isEqualTo(acks);

        // 不是本厂要用的物料（角鲨烷只用在自产的面霜里）当“不存在”
        long squSpec = read(qa, "/api/quality/items/" + itemId(qa, "RM-SQU-005") + "/spec").get("id").asLong();
        call(oem, PORTAL + "/" + squSpec + "/ack", Map.of()).andExpect(status().isNotFound());
        // 内部账号进不了门户接口
        mvc.perform(get(PORTAL).header("Authorization", "Bearer " + qa)).andExpect(status().isForbidden());

        // 精华瓶换版：确认的是旧版，进待办；门户不能再确认旧版
        long btlItem = itemId(qa, "PK-BTL-001");
        long oldSpec = btl.get("specId").asLong();
        long v2 = ok(call(qa, "/api/quality/specs", Map.of("itemId", btlItem, "changeNote", "加严容量", "lines", List.of(
                Map.of("name", "满口容量", "unit", "mL", "minValue", 30.5, "maxValue", 32.5, "coaAllowed", false))))).get("id").asLong();
        ok(call(qso, "/api/quality/specs/" + v2 + "/approve", Map.of("password", PWD)));
        JsonNode outdated = row(read(qa, "/api/oem-supply").get("rows"), "PK-BTL-001");
        assertThat(outdated.get("state").asText()).isEqualTo("OUTDATED");
        assertThat(outdated.get("specVersion").asInt()).isEqualTo(2);
        assertThat(read(qso, "/api/dashboard").get("todos").toString()).contains("确认的还是第 1 版");
        call(oem, PORTAL + "/" + oldSpec + "/ack", Map.of()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("已经换版")));

        // 线下交接：只给质量检验员、质量安全负责人；只能登记受托生产企业；同一版不重复登记
        call(buyer, "/api/oem-supply/specs/" + v2 + "/acks", Map.of("factoryId", a3, "receiver", "张三")).andExpect(status().isForbidden());
        call(qa, "/api/oem-supply/specs/" + v2 + "/acks", Map.of("factoryId", partnerId(qa, "SUP-HC"), "receiver", "张三"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不是受托生产企业")));
        call(qa, "/api/oem-supply/specs/" + v2 + "/acks", Map.of("factoryId", a3)).andExpect(status().isBadRequest());
        JsonNode off = ok(call(qa, "/api/oem-supply/specs/" + v2 + "/acks", Map.of("factoryId", a3, "receiver", "禾川 QA 张三", "note", "纸质版签收")));
        assertThat(off.get("method").asText()).isEqualTo("OFFLINE");
        assertThat(off.get("specVersion").asInt()).isEqualTo(2);
        call(qa, "/api/oem-supply/specs/" + v2 + "/acks", Map.of("factoryId", a3, "receiver", "李四"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("已经确认过")));
        assertThat(row(read(oem, PORTAL), "PK-BTL-001").get("state").asText()).isEqualTo("ACKED");

        // 包材都确认了：待办消失，自查第 12 项只剩没有标准的原料
        assertThat(read(qso, "/api/dashboard").get("todos").toString()).doesNotContain("受托厂没确认物料验收标准");
        String details = evidence(qa).get(12).get("details").toString();
        assertThat(details).doesNotContain("精华彩盒").doesNotContain("精华瓶").contains("透明质酸钠：品牌供料");

        // 开关打开后：首次发料前，品牌供料的物料都要有标准、受托厂确认过现行版本（烟酰胺、透明质酸钠还没有标准）
        long serum = itemId(qa, "FG-SER-001");
        String prod = login("prod1", PWD);
        long mo = ok(call(prod, "/api/mfg-orders", Map.of("factoryId", a3, "fgItemId", serum, "plannedQty", 10))).get("id").asLong();
        requireAck(true);
        try {
            call(prod, "/api/mfg-orders/" + mo + "/issue", Map.of()).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("烟酰胺 没有生效的质量标准")))
                    .andExpect(jsonPath("$.message", containsString("透明质酸钠 没有生效的质量标准")))
                    .andExpect(jsonPath("$.message", org.hamcrest.Matchers.not(containsString("精华瓶"))));
            assertThat(read(qa, "/api/oem-supply").get("requireAckForIssue").asBoolean()).isTrue();
        } finally {
            requireAck(false);
        }
        assertThat(read(qa, "/api/oem-supply").get("requireAckForIssue").asBoolean()).isFalse();
        ok(call(prod, "/api/mfg-orders/" + mo + "/issue", Map.of()));

        // 导出
        mvc.perform(get("/api/reports/oem-supply-specs").header("Authorization", "Bearer " + qa)).andExpect(status().isOk());
        mvc.perform(get("/api/reports/oem-spec-acks").header("Authorization", "Bearer " + qa)).andExpect(status().isOk());
    }
}
