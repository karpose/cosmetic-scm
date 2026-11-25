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
import com.cosmetic.scm.oemcontract.OemContractService;
import com.cosmetic.scm.production.ProductionService;
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

/** 委托生产合同（检查要点委托生产版第 8 项）。签批的合同会影响总览、上线检查和自查核对，用单独的库。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-oem-contract;MODE=MySQL;DB_CLOSE_DELAY=-1")
class OemContractTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;
    private static final String BASE = "/api/oem-contracts";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TimeProvider time;
    @Autowired ProductionService production;

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

    private void upload(String t, long id) throws Exception {
        mvc.perform(multipart("/api/attachments").file(new MockMultipartFile("file", "合同.pdf", "application/pdf", "%PDF-1.4 contract".getBytes(StandardCharsets.UTF_8)))
                .param("ownerType", "OEM_CONTRACT").param("ownerId", String.valueOf(id)).header("Authorization", "Bearer " + t)).andExpect(status().isOk());
    }

    private long idOf(String t, String url, String code) throws Exception {
        for (JsonNode n : read(t, url)) {
            if (code.equals(n.get("code").asText())) {
                return n.get("id").asLong();
            }
        }
        throw new AssertionError(code);
    }

    private Map<String, Object> contract(long factory, List<Long> products, LocalDate from, LocalDate to, String rm, String pk) {
        Map<String, Object> m = new HashMap<>();
        m.put("factoryId", factory);
        m.put("title", "委托加工合同");
        m.put("extNo", "EXT-1");
        m.put("signedOn", from.toString());
        m.put("validFrom", from.toString());
        m.put("validTo", to.toString());
        m.put("rmSupply", rm);
        m.put("pkSupply", pk);
        m.put("productIds", products);
        return m;
    }

    private static Map<String, Object> approve(List<String> checked, String password) {
        return Map.of("checked", checked, "password", password);
    }

    private JsonNode contractByNo(String t, String no) throws Exception {
        for (JsonNode c : read(t, BASE).get("contracts")) {
            if (c.get("contractNo").asText().equals(no)) {
                return c;
            }
        }
        throw new AssertionError(no);
    }

    private String todos(String t) throws Exception {
        return read(t, "/api/dashboard").get("todos").toString();
    }

    private void requireContract(boolean on) {
        Object target = AopTestUtils.getTargetObject(production);
        ReflectionTestUtils.setField(target, "requireContract", on);
    }

    @Test
    void contractNeedsQsoSignatureAndCoversOrdersByOrderDate() throws Exception {
        String admin = login("admin", "Admin@123456");
        String buyer = login("purchase1", PWD);
        String qso = login("qso1", PWD);
        String prod = login("prod1", PWD);
        LocalDate today = time.today();
        long a3 = idOf(admin, "/api/partners", "OEM-A3");
        long supplier = idOf(admin, "/api/partners", "SUP-HC");
        long own = idOf(admin, "/api/partners", "OWN-01");
        long serum = idOf(admin, "/api/items", "FG-SER-001");
        long cream = idOf(admin, "/api/items", "FG-CRM-002");
        long gly = idOf(admin, "/api/items", "RM-GLY-002");
        List<String> all = List.copyOf(OemContractService.CLAUSES.keySet());

        // 示例数据：禾川的合同已提交待签批，在做的委托单没有合同依据
        JsonNode o = read(buyer, BASE);
        assertThat(o.get("requireForOrder").asBoolean()).isFalse();
        assertThat(o.get("clauses").size()).isEqualTo(all.size());
        JsonNode demo = o.get("contracts").get(0);
        assertThat(demo.get("status").asText()).isEqualTo("PENDING");
        assertThat(demo.get("products").size()).isEqualTo(2);
        assertThat(demo.get("hasFile").asBoolean()).isFalse();
        assertThat(demo.get("mismatches").size()).isZero();
        String demoNo = demo.get("contractNo").asText();
        long demoId = demo.get("id").asLong();
        assertThat(todos(qso)).contains("委托生产合同待签批").contains(demoNo).contains("委托单没有委托生产合同");
        JsonNode orders = read(prod, BASE + "/orders");
        assertThat(orders.size()).isGreaterThanOrEqualTo(3);
        orders.forEach(x -> assertThat(x.get("covered").asBoolean()).isFalse());
        assertThat(read(admin, "/api/golive").toString()).contains("委托生产合同").contains("还没有执行中的委托生产合同");
        JsonNode chk = read(prod, BASE + "/check?factoryId=" + a3 + "&fgItemId=" + serum);
        assertThat(chk.get("applicable").asBoolean()).isTrue();
        assertThat(chk.get("covered").asBoolean()).isFalse();
        assertThat(read(prod, BASE + "/check?factoryId=" + own + "&fgItemId=" + cream).get("applicable").asBoolean()).isFalse();

        // 登记的校验和权限
        call(buyer, BASE, contract(supplier, List.of(serum), today, today.plusYears(1), "MIXED", "BRAND")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("不是受托生产企业")));
        call(buyer, BASE, contract(a3, List.of(serum), today, today.minusDays(1), "MIXED", "BRAND")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("结束日期不能早于开始日期")));
        call(buyer, BASE, contract(a3, List.of(gly), today, today.plusYears(1), "MIXED", "BRAND")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("不是成品")));
        Map<String, Object> future = contract(a3, List.of(serum), today, today.plusYears(1), "MIXED", "BRAND");
        future.put("signedOn", today.plusDays(1).toString());
        call(buyer, BASE, future).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("签订日期不能晚于今天")));
        call(buyer, BASE, contract(a3, List.of(), today, today.plusYears(1), "MIXED", "BRAND")).andExpect(status().isBadRequest());
        call(login("sales1", PWD), BASE, contract(a3, List.of(serum), today, today.plusYears(1), "MIXED", "BRAND")).andExpect(status().isForbidden());
        call(login("viewer1", PWD), BASE, contract(a3, List.of(serum), today, today.plusYears(1), "MIXED", "BRAND")).andExpect(status().isForbidden());

        // 签批：只给质量安全负责人；要全部约定事项、扫描件；不能签自己登记或提交的
        call(buyer, BASE + "/" + demoId + "/approve", approve(all, PWD)).andExpect(status().isForbidden());
        call(qso, BASE + "/" + demoId + "/approve", approve(all, "wrong")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("签批密码不正确")));
        call(qso, BASE + "/" + demoId + "/approve", approve(all.subList(0, 3), PWD)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("没有全部确认")));
        call(qso, BASE + "/" + demoId + "/approve", approve(all, PWD)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("还没有上传合同扫描件")));
        JsonNode own1 = ok(call(qso, BASE, contract(a3, List.of(serum), today, today.plusYears(1), "MIXED", "BRAND")));
        ok(call(qso, BASE + "/" + own1.get("id").asLong() + "/submit", Map.of()));
        upload(qso, own1.get("id").asLong());
        call(qso, BASE + "/" + own1.get("id").asLong() + "/approve", approve(all, PWD)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("不能是合同的登记人或提交人")));
        // 退回后回到草稿，可以改，也可以作废
        JsonNode back = ok(call(qso, BASE + "/" + own1.get("id").asLong() + "/reject", Map.of("note", "自己登记的，换人签")));
        assertThat(back.get("status").asText()).isEqualTo("DRAFT");
        assertThat(back.get("rejectNote").asText()).isEqualTo("自己登记的，换人签");
        Map<String, Object> changed = contract(a3, List.of(serum, cream), today, today.plusYears(1), "BRAND", "BRAND");
        JsonNode edited = ok(mvc.perform(put(BASE + "/" + own1.get("id").asLong()).header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(changed))));
        assertThat(edited.get("products").size()).isEqualTo(2);
        // 合同写原料全部由委托方提供，精华液 BOM 里甘油、苯氧乙醇是工厂代采
        assertThat(edited.get("mismatches").toString()).contains("甘油").contains("工厂代采").contains("委托方提供");
        assertThat(ok(call(buyer, BASE + "/" + own1.get("id").asLong() + "/void", Map.of("note", "登记重复"))).get("status").asText()).isEqualTo("VOID");

        // 示例合同上传扫描件后签批生效：期限覆盖下单日期的委托单都有了依据
        upload(buyer, demoId);
        JsonNode signed = ok(call(qso, BASE + "/" + demoId + "/approve", approve(all, PWD)));
        assertThat(signed.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(signed.get("state").asText()).isEqualTo("IN_EFFECT");
        assertThat(signed.get("approvedBy").asText()).isEqualTo("qso1");
        assertThat(signed.get("checked").size()).isEqualTo(all.size());
        mvc.perform(put(BASE + "/" + demoId).header("Authorization", "Bearer " + buyer).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(changed))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不能修改")));
        read(prod, BASE + "/orders").forEach(x -> assertThat(x.get("covered").asBoolean()).isTrue());
        assertThat(todos(qso)).doesNotContain("委托生产合同待签批").doesNotContain("委托单没有委托生产合同");
        assertThat(read(admin, "/api/golive").toString()).contains("1 家受托厂都有执行中的合同");
        chk = read(prod, BASE + "/check?factoryId=" + a3 + "&fgItemId=" + serum);
        assertThat(chk.get("covered").asBoolean()).isTrue();
        assertThat(chk.get("contractNo").asText()).isEqualTo(demoNo);

        // 下委托单记下依据的合同
        JsonNode mo = ok(call(prod, "/api/mfg-orders", Map.of("factoryId", a3, "fgItemId", serum, "plannedQty", 100)));
        assertThat(read(admin, "/api/audit?q=" + mo.get("moNo").asText()).toString()).contains("依据合同 " + demoNo);

        // 自查第 8 项：期间内的委托单都有合同
        JsonNode check = ok(call(login("qa1", PWD), "/api/qms/self-checks",
                Map.of("periodFrom", today.minusMonths(6).toString(), "periodTo", today.toString(), "scope", "全部委托生产产品")));
        JsonNode ev = read(qso, "/api/qms/self-checks/" + check.get("check").get("id").asLong() + "/evidence");
        JsonNode item8 = null;
        JsonNode item7 = null;
        for (JsonNode e : ev) {
            if (e.get("itemNo").asInt() == 8) {
                item8 = e;
            } else if (e.get("itemNo").asInt() == 7) {
                item7 = e;
            }
        }
        assertThat(item8).isNotNull();
        assertThat(item8.get("level").asText()).isEqualTo("OK");
        assertThat(item8.get("summary").asText()).contains("都有覆盖该成品的有效委托生产合同");
        assertThat(item7.get("details").toString()).contains("执行中的合同 " + demoNo);

        // 终止：之前下的单仍有依据，之后下的单没有；打开开关后没有合同不能下委托单
        call(buyer, BASE + "/" + demoId + "/terminate", Map.of("note", "更换受托厂")).andExpect(status().isForbidden());
        JsonNode ended = ok(call(qso, BASE + "/" + demoId + "/terminate", Map.of("note", "更换受托厂")));
        assertThat(ended.get("state").asText()).isEqualTo("TERMINATED");
        assertThat(ended.get("endReason").asText()).isEqualTo("更换受托厂");
        assertThat(ended.get("terminatedAt").asText()).doesNotContain(".");
        read(prod, BASE + "/orders").forEach(x -> assertThat(x.get("covered").asBoolean()).isTrue());
        Thread.sleep(1100); // 时间按整秒记，终止那一秒内下的单算终止前
        JsonNode later = ok(call(prod, "/api/mfg-orders", Map.of("factoryId", a3, "fgItemId", cream, "plannedQty", 100)));
        assertThat(todos(prod)).contains("委托单没有委托生产合同").contains(later.get("moNo").asText());
        assertThat(read(admin, "/api/audit?q=" + later.get("moNo").asText()).toString()).contains("没有有效的委托生产合同");
        requireContract(true);
        try {
            call(prod, "/api/mfg-orders", Map.of("factoryId", a3, "fgItemId", cream, "plannedQty", 100)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", containsString("没有覆盖")));
            // 自有工厂不用合同
            ok(call(prod, "/api/mfg-orders", Map.of("factoryId", own, "fgItemId", cream, "plannedQty", 100)));
        } finally {
            requireContract(false);
        }

        // 快到期提醒：精华液的新合同 10 天后到期；续签（另一份到期更晚的合同覆盖全部成品）后不再提醒
        JsonNode shortOne = ok(call(buyer, BASE, contract(a3, List.of(serum), today.minusDays(5), today.plusDays(10), "MIXED", "BRAND")));
        upload(buyer, shortOne.get("id").asLong());
        ok(call(buyer, BASE + "/" + shortOne.get("id").asLong() + "/submit", Map.of()));
        JsonNode s1 = ok(call(qso, BASE + "/" + shortOne.get("id").asLong() + "/approve", approve(all, PWD)));
        assertThat(s1.get("expiringSoon").asBoolean()).isTrue();
        assertThat(s1.get("daysLeft").asLong()).isEqualTo(10);
        assertThat(todos(qso)).contains("委托生产合同快到期").contains(s1.get("contractNo").asText());
        requireContract(true);
        try {
            ok(call(prod, "/api/mfg-orders", Map.of("factoryId", a3, "fgItemId", serum, "plannedQty", 100)));
        } finally {
            requireContract(false);
        }
        Map<String, Object> next = contract(a3, List.of(serum, cream), today.plusDays(11), today.plusYears(1), "MIXED", "BRAND");
        next.put("signedOn", today.toString());
        JsonNode renewal = ok(call(buyer, BASE, next));
        upload(buyer, renewal.get("id").asLong());
        ok(call(buyer, BASE + "/" + renewal.get("id").asLong() + "/submit", Map.of()));
        JsonNode r = ok(call(qso, BASE + "/" + renewal.get("id").asLong() + "/approve", approve(all, PWD)));
        assertThat(r.get("state").asText()).isEqualTo("NOT_STARTED");
        assertThat(contractByNo(qso, s1.get("contractNo").asText()).get("expiringSoon").asBoolean()).isFalse();
        assertThat(todos(qso)).doesNotContain("委托生产合同快到期");

        // 导出
        mvc.perform(get("/api/reports/oem-contracts").header("Authorization", "Bearer " + qso)).andExpect(status().isOk());
    }
}
