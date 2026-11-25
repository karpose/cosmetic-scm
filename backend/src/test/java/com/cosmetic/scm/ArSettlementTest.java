package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.integration.IntegrationLogRepository;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.ItemRepository;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.PartnerRepository;
import com.cosmetic.scm.master.WarehouseRepository;
import com.cosmetic.scm.sales.SalesOrder;
import com.cosmetic.scm.sales.SalesOrderRepository;
import com.cosmetic.scm.sales.SoLine;
import com.cosmetic.scm.sales.SoLineRepository;
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

/** 客户应收对账与回款核销（V42）：生成、开票、审批、外部应收凭证拉取/回执与幂等回款回调。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-ar-settlement;MODE=MySQL;DB_CLOSE_DELAY=-1")
class ArSettlementTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;
    private static final String API_KEY = "test-integration-key";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PartnerRepository partners;
    @Autowired ItemRepository items;
    @Autowired WarehouseRepository warehouses;
    @Autowired SalesOrderRepository salesOrders;
    @Autowired SoLineRepository soLines;
    @Autowired TimeProvider time;
    @Autowired IntegrationLogRepository integrationLogs;

    private LocalDate d(int n) {
        return time.today().plusDays(n);
    }

    private String login(String u) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", u, "password", PWD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    private ResultActions call(String token, String url, Object body) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions read(String token, String url) throws Exception {
        return mvc.perform(get(url).header("Authorization", "Bearer " + token));
    }

    private ResultActions ackAr(String statementNo, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/integration/finance/ar-vouchers/" + statementNo + "/ack")
                .header("X-Api-Key", API_KEY).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void arStatementVoucherAndIdempotentReceiptCallbackWorkEndToEnd() throws Exception {
        Partner customer = partners.findByCode("CUS-AR-42").orElseGet(() -> {
            Partner n = new Partner();
            n.setCode("CUS-AR-42");
            n.setName("应收测试客户");
            n.setKind(PartnerKind.CUSTOMER);
            n.setTaxId("91310000MA1FL0042X");
            n.setDefaultCreditDays(60);
            n.setDefaultTaxRate(new BigDecimal("13.00"));
            n.setFinanceCode("ERP-CUS-042");
            return partners.save(n);
        });
        Item fg = items.findAll().stream().filter(i -> i.getItemType() == ItemType.FG).findFirst().orElseThrow();
        long whId = warehouses.findAll().stream().filter(w -> "WH-MAIN".equals(w.getCode())).findFirst().orElseThrow().getId();

        SalesOrder so = new SalesOrder();
        so.setSoNo("SO-AR-42-001");
        so.setCustomerId(customer.getId());
        so.setWarehouseId(whId);
        so.setOrderDate(d(0));
        so.setStatus("SHIPPED");
        so.setCreatedBy("sales1");
        so.setCreatedAt(time.now());
        salesOrders.save(so);

        SoLine line = new SoLine();
        line.setSoId(so.getId());
        line.setItemId(fg.getId());
        line.setQty(new BigDecimal("10"));
        line.setUnitPrice(new BigDecimal("113.00"));
        line.setTaxRate(new BigDecimal("13.00"));
        soLines.save(line);

        read(login("qa1"), "/api/ar-statements").andExpect(status().isForbidden());
        read(login("viewer1"), "/api/ar-statements").andExpect(status().isForbidden());

        String sales = login("sales1");
        String fin = login("finance1");

        JsonNode ar = body(call(sales, "/api/ar-statements", Map.of(
                "customerId", customer.getId(),
                "periodTo", d(0).toString()
        )).andExpect(status().isOk()));
        long arId = ar.get("id").asLong();
        String arNo = ar.get("statementNo").asText();
        assertThat(ar.get("creditDays").asInt()).isEqualTo(60);
        assertThat(ar.get("totalAmount").decimalValue()).isEqualByComparingTo("1130.00");
        assertThat(ar.get("taxAmount").decimalValue()).isEqualByComparingTo("130.00");

        call(fin, "/api/ar-statements/" + arId + "/invoice", Map.of(
                "invoiceNo", "OUT-INV-042", "invoiceDate", d(0).toString()
        )).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INVOICED"));
        call(fin, "/api/ar-statements/" + arId + "/approve", Map.of("password", PWD))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));

        JsonNode vouchers = body(mvc.perform(get("/api/integration/finance/ar-vouchers")
                        .header("X-Api-Key", API_KEY).param("pendingOnly", "true"))
                .andExpect(status().isOk()));
        assertThat(vouchers.findValuesAsText("statementNo")).contains(arNo);

        mvc.perform(post("/api/integration/finance/ar-vouchers/" + arNo + "/ack")
                        .header("X-Api-Key", API_KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("success", true, "extVoucherNo", "AR-VOUCH-042"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.financeSyncStatus").value("SYNCED"))
                .andExpect(jsonPath("$.extVoucherNo").value("AR-VOUCH-042"));

        Map<String, Object> cb = Map.of(
                "statementNo", arNo,
                "extReceiptNo", "REC-BANK-042-1",
                "receiptDate", d(0).toString(),
                "amount", new BigDecimal("1130.00")
        );
        mvc.perform(post("/api/integration/finance/receipts")
                        .header("X-Api-Key", API_KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(cb)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.unreceived").value(0.0));

        mvc.perform(post("/api/integration/finance/receipts")
                        .header("X-Api-Key", API_KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(cb)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));

        // 同一外部回款单号、内容不同：409，不重复核销
        Map<String, Object> changed = new java.util.HashMap<>(cb);
        changed.put("amount", new BigDecimal("1000.00"));
        mvc.perform(post("/api/integration/finance/receipts")
                        .header("X-Api-Key", API_KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(changed)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("与已登记的回款信息不一致")));
        Map<String, Object> otherDate = new java.util.HashMap<>(cb);
        otherDate.put("receiptDate", d(-1).toString());
        mvc.perform(post("/api/integration/finance/receipts")
                        .header("X-Api-Key", API_KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(otherDate)))
                .andExpect(status().isConflict());

        // 凭证回执：已同步后换凭证号 409，原号重试照常返回，迟到的失败回执不改状态
        ackAr(arNo, Map.of("success", true, "extVoucherNo", "AR-VOUCH-OTHER"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("AR-VOUCH-042")));
        ackAr(arNo, Map.of("success", true, "extVoucherNo", "AR-VOUCH-042"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.extVoucherNo").value("AR-VOUCH-042"));
        ackAr(arNo, Map.of("success", false, "note", "迟到的失败"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.financeSyncStatus").value("SYNCED"))
                .andExpect(jsonPath("$.extVoucherNo").value("AR-VOUCH-042"));

        // 失败的回调也写接口日志（业务事务回滚了，日志另开事务）
        mvc.perform(post("/api/integration/finance/receipts")
                        .header("X-Api-Key", API_KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("statementNo", "AR-NOT-EXIST", "extReceiptNo", "REC-NONE",
                                "receiptDate", d(0).toString(), "amount", new BigDecimal("1.00")))))
                .andExpect(status().isNotFound());
        var logs = integrationLogs.findTop200ByOrderByIdDesc();
        assertThat(logs).anyMatch(l -> "回款回调核销".equals(l.getAction()) && "AR-NOT-EXIST".equals(l.getRef()) && !l.isSuccess());
        assertThat(logs).anyMatch(l -> "回款回调核销".equals(l.getAction()) && arNo.equals(l.getRef()) && !l.isSuccess()
                && l.getMessage().contains("不一致"));
        assertThat(logs).anyMatch(l -> "应收凭证回执".equals(l.getAction()) && arNo.equals(l.getRef()) && !l.isSuccess()
                && l.getMessage().contains("已绑定外部凭证号"));
        assertThat(logs).anyMatch(l -> "应收凭证回执".equals(l.getAction()) && arNo.equals(l.getRef()) && l.isSuccess()
                && "已同步，忽略迟到的失败回执".equals(l.getMessage()));

        read(fin, "/api/reports/ar-statements").andExpect(status().isOk());
        read(login("qa1"), "/api/reports/ar-statements").andExpect(status().isForbidden());
    }
}
