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

/** 产品销售记录（检查要点委托生产版第 19 项）：出库时记快照，价格取出库单或对账单，没权限不显示价格，缺的内容标出来并提醒。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-sales-record;MODE=MySQL;DB_CLOSE_DELAY=-1")
class SalesRecordTest {

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

    private String login(String u) throws Exception {
        return login(u, PWD);
    }

    private ResultActions call(String t, String url, Object body) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + t).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private JsonNode read(String t, String url) throws Exception {
        return json.readTree(mvc.perform(get(url).header("Authorization", "Bearer " + t)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode ok(ResultActions r) throws Exception {
        return json.readTree(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private Map<String, Object> customer(String code, String contact, String address) {
        Map<String, Object> m = new HashMap<>();
        m.put("code", code);
        m.put("name", "销售记录客户" + code);
        m.put("kind", "CUSTOMER");
        m.put("channel", "线下经销");
        m.put("minShelfPct", 0);
        m.put("contact", contact);
        m.put("address", address);
        m.put("qualified", true);
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

    private JsonNode rowOf(JsonNode records, String soNo) {
        for (JsonNode r : records.get("rows")) {
            if (r.get("soNo").asText().equals(soNo)) {
                return r;
            }
        }
        throw new AssertionError("销售记录里没有 " + soNo);
    }

    private static String sheetOf(byte[] xlsx) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (var z = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(xlsx), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (e.getName().startsWith("xl/")) {
                    sb.append(new String(z.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return sb.toString();
    }

    @Test
    void salesRecordsKeepWhatWasTrueAtShipmentAndFlagWhatIsMissing() throws Exception {
        String admin = login("admin", "Admin@123456");
        String sales = login("sales1");
        String qa = login("qa1");
        LocalDate today = time.today();
        long serum = jdbc.queryForObject("select id from item where code = 'FG-SER-001'", Long.class);
        long wh = jdbc.queryForObject("select id from warehouse where code = 'WH-MAIN'", Long.class);

        long c1 = ok(call(admin, "/api/partners", customer("CUS-SR1", "张经理 13800000001", "上海市示例路 1 号"))).get("id").asLong();
        long c2 = ok(call(admin, "/api/partners", customer("CUS-SR2", null, null))).get("id").asLong();

        // 信息齐全的出库：没有缺项
        JsonNode so1 = ok(call(sales, "/api/sales-orders", ship(c1, wh, serum, "2", "99")));
        assertThat(so1.get("recordGaps")).isEmpty();
        // 购买者没地址、没联系方式，也没定价：出库照常，标出缺的内容
        JsonNode so2 = ok(call(sales, "/api/sales-orders", ship(c2, wh, serum, "1", null)));
        assertThat(so2.get("recordGaps").toString()).contains("没有价格").contains("购买者没有地址").contains("购买者没有联系方式");

        JsonNode rec = read(sales, "/api/sales-orders/records");
        assertThat(rec.get("priceVisible").asBoolean()).isTrue();
        assertThat(rec.get("from").asText()).isEqualTo(today.minusDays(29).toString());
        JsonNode r1 = rowOf(rec, so1.get("soNo").asText());
        assertThat(r1.get("filingNo").asText()).isEqualTo("示例备案编号-0001");
        assertThat(r1.get("netContent").asText()).isEqualTo("30mL");
        assertThat(r1.get("expDate").isNull()).isFalse();
        assertThat(r1.get("batchNo").asText()).isNotBlank();
        assertThat(r1.get("unitPrice").decimalValue()).isEqualByComparingTo("99");
        assertThat(r1.get("amount").decimalValue()).isEqualByComparingTo("198.00");
        assertThat(r1.get("priceSource").asText()).isEqualTo("出库单");
        assertThat(r1.get("buyerAddress").asText()).isEqualTo("上海市示例路 1 号");
        assertThat(r1.get("buyerContact").asText()).isEqualTo("张经理 13800000001");
        assertThat(r1.get("gaps")).isEmpty();
        JsonNode r2 = rowOf(rec, so2.get("soNo").asText());
        assertThat(r2.get("gaps").toString()).contains("没有价格").contains("购买者没有地址");
        assertThat(rec.get("gapSummary").toString()).contains("没有价格");

        // 没有价格权限：价格和金额不显示，缺价格照样标出来
        JsonNode recQa = read(qa, "/api/sales-orders/records");
        assertThat(recQa.get("priceVisible").asBoolean()).isFalse();
        assertThat(rowOf(recQa, so1.get("soNo").asText()).get("unitPrice").isNull()).isTrue();
        assertThat(rowOf(recQa, so1.get("soNo").asText()).get("amount").isNull()).isTrue();
        assertThat(rowOf(recQa, so2.get("soNo").asText()).get("gaps").toString()).contains("没有价格");

        // 改了客户地址：已出库的记录不变，之后的出库用新地址
        Map<String, Object> changed = customer("CUS-SR1", "李经理 13900000002", "上海市新地址 8 号");
        mvc.perform(put("/api/partners/" + c1).header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(changed))).andExpect(status().isOk());
        JsonNode so3 = ok(call(sales, "/api/sales-orders", ship(c1, wh, serum, "1", "99")));
        rec = read(sales, "/api/sales-orders/records");
        assertThat(rowOf(rec, so1.get("soNo").asText()).get("buyerAddress").asText()).isEqualTo("上海市示例路 1 号");
        assertThat(rowOf(rec, so3.get("soNo").asText()).get("buyerAddress").asText()).isEqualTo("上海市新地址 8 号");

        // V50 之前的出库没有快照：按当前主数据显示
        long so3id = so3.get("id").asLong();
        jdbc.update("update sales_order set buyer_name = null, buyer_address = null, buyer_contact = null where id = ?", so3id);
        jdbc.update("update so_line set filing_no = null, net_content = null where so_id = ?", so3id);
        JsonNode old = rowOf(read(sales, "/api/sales-orders/records"), so3.get("soNo").asText());
        assertThat(old.get("buyerContact").asText()).isEqualTo("李经理 13900000002");
        assertThat(old.get("netContent").asText()).isEqualTo("30mL");
        assertThat(old.get("filingNo").asText()).isEqualTo("示例备案编号-0001");

        // 出库时没定价，对账时定了价：销售记录取对账单上的价
        ok(call(sales, "/api/ar-statements", Map.of("customerId", c2, "periodTo", today.toString(), "itemUnitPrices", Map.of(String.valueOf(serum), "88"))));
        JsonNode billed = rowOf(read(sales, "/api/sales-orders/records"), so2.get("soNo").asText());
        assertThat(billed.get("unitPrice").decimalValue()).isEqualByComparingTo("88");
        assertThat(billed.get("priceSource").asText()).isEqualTo("对账单");
        assertThat(billed.get("gaps").toString()).doesNotContain("没有价格").contains("购买者没有地址");

        // 总览提醒：缺内容的行
        assertThat(read(admin, "/api/dashboard").toString()).contains("销售记录不全").contains("购买者没有地址");

        // 日期范围
        mvc.perform(get("/api/sales-orders/records?from=" + today + "&to=" + today.minusDays(1)).header("Authorization", "Bearer " + sales))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("开始日期不能晚于结束日期")));
        mvc.perform(get("/api/sales-orders/records?from=" + today.minusDays(400) + "&to=" + today).header("Authorization", "Bearer " + sales))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("一年")));
        assertThat(read(sales, "/api/sales-orders/records?from=" + today.minusDays(365) + "&to=" + today).get("rows").size()).isGreaterThanOrEqualTo(3);

        // 导出：QA 导出没有价格列的内容，销售导出有
        String qaSheet = sheetOf(mvc.perform(get("/api/reports/sales-records").header("Authorization", "Bearer " + qa)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertThat(qaSheet).contains("产品销售记录").contains("含税单价（无权限）").contains("30mL").contains("上海市示例路 1 号").contains(so1.get("soNo").asText());
        String salesSheet = sheetOf(mvc.perform(get("/api/reports/sales-records?from=" + today + "&to=" + today).header("Authorization", "Bearer " + sales))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(salesSheet).contains("198").contains("对账单");
        assertThat(read(admin, "/api/audit?q=产品销售记录").toString()).contains("导出报表");

        // 主数据：标签净含量只给成品，上线检查有净含量
        assertThat(read(admin, "/api/items").toString()).contains("\"netLabel\":\"30mL\"");
        assertThat(read(admin, "/api/golive").toString()).contains("净含量");
    }
}
