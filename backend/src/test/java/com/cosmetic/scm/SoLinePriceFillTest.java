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

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.demo.DemoDataLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 出库行补单价：出库时没单价也没协议价的行，补上以后进应收、占信用额度；改已有单价和 0 元要写原因；
 * 进了应收对账单的不能改；只给销售、财务、管理员；总览和授信概览列出缺单价的行。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-so-line-price;MODE=MySQL;DB_CLOSE_DELAY=-1")
class SoLinePriceFillTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired TimeProvider time;

    private String login(String u, String p) throws Exception {
        String r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", u, "password", p)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(r).get("token").asText();
    }

    private ResultActions call(String t, String url, Object body) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions get0(String t, String url) throws Exception {
        return mvc.perform(get(url).header("Authorization", "Bearer " + t));
    }

    private JsonNode read(String t, String url) throws Exception {
        return ok(get0(t, url));
    }

    private JsonNode ok(ResultActions r) throws Exception {
        return json.readTree(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private Map<String, Object> customer(String code, String creditLimit) {
        Map<String, Object> m = new HashMap<>();
        m.put("code", code);
        m.put("name", "补单价客户" + code);
        m.put("kind", "CUSTOMER");
        m.put("channel", "线下经销");
        m.put("minShelfPct", 0);
        m.put("contact", "王经理 13800000003");
        m.put("address", "上海市示例路 3 号");
        m.put("qualified", true);
        m.put("defaultTaxRate", 13);
        if (creditLimit != null) {
            m.put("creditLimit", creditLimit);
        }
        return m;
    }

    private Map<String, Object> ship(long customerId, long whId, long itemId, String qty, String price) {
        Map<String, Object> line = new HashMap<>();
        line.put("itemId", itemId);
        line.put("qty", qty);
        if (price != null) {
            line.put("unitPrice", price);
        }
        return Map.of("customerId", customerId, "warehouseId", whId, "lines", List.of(line));
    }

    private static Map<String, Object> price(long lineId, String unitPrice) {
        Map<String, Object> m = new HashMap<>();
        m.put("soLineId", lineId);
        m.put("unitPrice", unitPrice);
        return m;
    }

    private static Map<String, Object> req(String reason, Map<String, Object>... lines) {
        Map<String, Object> m = new HashMap<>();
        m.put("lines", List.of(lines));
        if (reason != null) {
            m.put("reason", reason);
        }
        return m;
    }

    private JsonNode creditRow(String token, long customerId) throws Exception {
        for (JsonNode r : read(token, "/api/ar-statements/credit-summary")) {
            if (r.get("customerId").asLong() == customerId) {
                return r;
            }
        }
        throw new AssertionError("授信概览里没有客户 " + customerId);
    }

    private JsonNode soLine(String token, String soNo) throws Exception {
        for (JsonNode so : read(token, "/api/sales-orders")) {
            if (so.get("soNo").asText().equals(soNo)) {
                return so.get("lines").get(0);
            }
        }
        throw new AssertionError("出库单列表里没有 " + soNo);
    }

    @Test
    void unpricedShipmentsCanBePricedLaterAndThenCountTowardReceivablesAndCredit() throws Exception {
        String admin = login("admin", "Admin@123456");
        String sales = login("sales1", PWD);
        String finance = login("finance1", PWD);
        String qa = login("qa1", PWD);
        LocalDate today = time.today();
        long serum = jdbc.queryForObject("select id from item where code = 'FG-SER-001'", Long.class);
        long wh = jdbc.queryForObject("select id from warehouse where code = 'WH-MAIN'", Long.class);
        long c1 = ok(call(admin, "/api/partners", customer("CUS-PF1", "1000"))).get("id").asLong();
        long c2 = ok(call(admin, "/api/partners", customer("CUS-PF2", null))).get("id").asLong();

        // 没单价、没协议价的出库照常发货，但不占额度
        JsonNode so1 = ok(call(sales, "/api/sales-orders", ship(c1, wh, serum, "3", null)));
        String so1No = so1.get("soNo").asText();
        long line1 = so1.get("lines").get(0).get("lineId").asLong();
        assertThat(so1.get("lines").get(0).get("unitPrice").isNull()).isTrue();
        JsonNode other = ok(call(sales, "/api/sales-orders", ship(c2, wh, serum, "1", null)));
        long otherLine = other.get("lines").get(0).get("lineId").asLong();

        JsonNode cr = creditRow(sales, c1);
        assertThat(cr.get("unbilledShipped").decimalValue()).isEqualByComparingTo("0");
        assertThat(cr.get("unpricedLines").asLong()).isEqualTo(1);

        // 缺单价列表：只给有销售价格权限的
        JsonNode list = read(sales, "/api/sales-orders/unpriced-lines?customerId=" + c1);
        assertThat(list.get("total").asLong()).isEqualTo(1);
        assertThat(list.get("rows").get(0).get("lineId").asLong()).isEqualTo(line1);
        assertThat(list.get("rows").get(0).get("suggestedPrice").isNull()).isTrue();
        assertThat(list.get("rows").get(0).get("defaultTaxRate").decimalValue()).isEqualByComparingTo("13");
        assertThat(list.get("customers").toString()).contains("CUS-PF1").contains("CUS-PF2");
        assertThat(read(sales, "/api/sales-orders/unpriced-lines?customerId=" + c1 + "&to=" + today.minusDays(1)).get("total").asLong()).isZero();
        get0(qa, "/api/sales-orders/unpriced-lines").andExpect(status().isForbidden());
        call(qa, "/api/sales-orders/customers/" + c1 + "/line-prices", req(null, price(line1, "200"))).andExpect(status().isForbidden());
        call(login("prod1", PWD), "/api/sales-orders/customers/" + c1 + "/line-prices", req(null, price(line1, "200"))).andExpect(status().isForbidden());

        // 总览待办（不带金额）
        String dash = read(sales, "/api/dashboard").toString();
        assertThat(dash).contains("出库行没有单价").contains("补单价客户CUS-PF1").contains("/sales?tab=unpriced&customerId=" + c1);

        // 生成应收单：没有可结算的行时说清楚是缺单价
        call(sales, "/api/ar-statements", Map.of("customerId", c1, "periodTo", today.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("1 行出库没有单价")));

        // 别的客户的行当作不存在；0 元要写原因；同一行填两次不行
        call(sales, "/api/sales-orders/customers/" + c1 + "/line-prices", req(null, price(otherLine, "100"))).andExpect(status().isNotFound());
        call(sales, "/api/sales-orders/customers/" + c1 + "/line-prices", req(null, price(line1, "0")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("原因")));
        call(sales, "/api/sales-orders/customers/" + c1 + "/line-prices", req(null, price(line1, "200"), price(line1, "210")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("填了两次")));
        call(sales, "/api/sales-orders/customers/" + c1 + "/line-prices", req(null, price(line1, "-1")))
                .andExpect(status().isBadRequest());

        // 补单价：进额度、出库单和销售记录显示单价
        JsonNode r = ok(call(sales, "/api/sales-orders/customers/" + c1 + "/line-prices", req(null, price(line1, "200"))));
        assertThat(r.get("updated").asInt()).isEqualTo(1);
        assertThat(r.get("amount").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(r.get("creditUsed").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(r.get("overLimit").asBoolean()).isFalse();
        assertThat(r.get("unpricedLeft").asLong()).isZero();
        cr = creditRow(sales, c1);
        assertThat(cr.get("unbilledShipped").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(cr.get("unpricedLines").asLong()).isZero();
        JsonNode l1 = soLine(sales, so1No);
        assertThat(l1.get("unitPrice").decimalValue()).isEqualByComparingTo("200");
        assertThat(l1.get("taxRate").decimalValue()).isEqualByComparingTo("13");
        assertThat(l1.get("billed").asBoolean()).isFalse();
        assertThat(soLine(qa, so1No).get("unitPrice").isNull()).isTrue();
        assertThat(soLine(qa, so1No).get("billed").isNull()).isTrue();
        assertThat(read(sales, "/api/sales-orders/records").toString()).contains(so1No);
        assertThat(read(sales, "/api/dashboard").toString()).doesNotContain("/sales?tab=unpriced&customerId=" + c1 + "\"");
        String audit = read(admin, "/api/audit?q=" + so1No).toString();
        assertThat(audit).contains("补录出库单价").contains("未定价").contains("200");
        // 审计里的金额只有采购、财务、管理员能看
        assertThat(read(qa, "/api/audit?q=" + so1No).toString()).doesNotContain("→ ¥200");

        // 更正已有单价：要写原因；没变化的不保存
        call(sales, "/api/sales-orders/customers/" + c1 + "/line-prices", req(null, price(line1, "250")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("原因")));
        call(sales, "/api/sales-orders/customers/" + c1 + "/line-prices", req("重复提交", price(line1, "200")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("没有要保存的")));
        ok(call(finance, "/api/sales-orders/customers/" + c1 + "/line-prices", req("按合同价更正", price(line1, "250"))));
        assertThat(read(admin, "/api/audit?q=" + so1No).toString()).contains("更正出库单价").contains("按合同价更正");

        // 补上的价占额度：750 + 本次 150×2 = 1050 > 1000，出库被拦
        call(sales, "/api/sales-orders", ship(c1, wh, serum, "2", "150"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("已出库未对账 ¥750.00")));

        // 再出一行没单价的：额度不足提示里带上没单价的行数；有前一次的单价做建议
        JsonNode so2 = ok(call(sales, "/api/sales-orders", ship(c1, wh, serum, "1", null)));
        long line2 = so2.get("lines").get(0).get("lineId").asLong();
        call(sales, "/api/sales-orders", ship(c1, wh, serum, "2", "150"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("另有 1 行出库还没有单价")));
        JsonNode row2 = read(sales, "/api/sales-orders/unpriced-lines?customerId=" + c1).get("rows").get(0);
        assertThat(row2.get("lineId").asLong()).isEqualTo(line2);
        assertThat(row2.get("suggestedPrice").decimalValue()).isEqualByComparingTo("250");
        assertThat(row2.get("suggestedSource").asText()).contains(so1No);

        // 补价后超额也照样保存（货已发出），返回超额提示：750 + 300 = 1050
        r = ok(call(sales, "/api/sales-orders/customers/" + c1 + "/line-prices", req(null, price(line2, "300"))));
        assertThat(r.get("overLimit").asBoolean()).isTrue();
        assertThat(r.get("creditUsed").decimalValue()).isEqualByComparingTo("1050.00");
        assertThat(creditRow(sales, c1).get("overLimit").asBoolean()).isTrue();

        // 生成应收单：两行都按补上的价进来；进了对账单的行不能再改
        JsonNode ar = ok(call(sales, "/api/ar-statements", Map.of("customerId", c1, "periodTo", today.toString())));
        assertThat(ar.get("goodsAmount").decimalValue()).isEqualByComparingTo("1050.00");
        assertThat(soLine(sales, so1No).get("billed").asBoolean()).isTrue();
        call(finance, "/api/sales-orders/customers/" + c1 + "/line-prices", req("改价", price(line1, "260")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString(ar.get("statementNo").asText())));
        // 作废对账单以后可以改
        ok(call(finance, "/api/ar-statements/" + ar.get("id").asLong() + "/cancel", Map.of("reason", "单价有误")));
        ok(call(finance, "/api/sales-orders/customers/" + c1 + "/line-prices", req("改价", price(line1, "260"))));

        // 赠品按 0 元定价：写了原因就行，不再算缺单价，也不进应收
        r = ok(call(sales, "/api/sales-orders/customers/" + c2 + "/line-prices", req("渠道试用装，不收款", price(otherLine, "0"))));
        assertThat(r.get("unpricedLeft").asLong()).isZero();
        assertThat(creditRow(sales, c2).get("unbilledShipped").decimalValue()).isEqualByComparingTo("0");
        call(sales, "/api/ar-statements", Map.of("customerId", c2, "periodTo", today.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("没有可结算")));
        assertThat(read(sales, "/api/sales-orders/unpriced-lines?customerId=" + c2).get("total").asLong()).isZero();
    }
}
