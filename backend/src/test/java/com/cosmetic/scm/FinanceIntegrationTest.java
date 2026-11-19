package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.Role;
import com.cosmetic.scm.integration.IntegrationLogRepository;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.master.ItemRepository;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.PartnerRepository;
import com.cosmetic.scm.master.WarehouseRepository;
import com.cosmetic.scm.security.UserRepository;
import com.cosmetic.scm.security.UserService;
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

/** 外部财务系统对接：应付凭证拉取/导出、凭证回执、付款水单幂等回调与接口权限隔离。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FinanceIntegrationTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;
    private static final String API_KEY = "test-integration-key";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PartnerRepository partners;
    @Autowired ItemRepository items;
    @Autowired WarehouseRepository warehouses;
    @Autowired BatchRepository batches;
    @Autowired UserRepository users;
    @Autowired UserService userService;
    @Autowired IntegrationLogRepository integrationLogs;
    @Autowired TimeProvider time;

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

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private Long item(String code) {
        return items.findAll().stream().filter(i -> code.equals(i.getCode())).findFirst().orElseThrow().getId();
    }

    private Long warehouseId() {
        return warehouses.findAll().stream().filter(w -> "WH-MAIN".equals(w.getCode())).findFirst().orElseThrow().getId();
    }

    private Long supplierWithFinanceProfile(String code, String username) {
        Partner p = partners.findByCode(code).orElseGet(() -> {
            Partner n = new Partner();
            n.setCode(code);
            n.setName(code + " 供应商");
            n.setKind(PartnerKind.SUPPLIER);
            n.setQualified(true);
            n.setTaxId("91310000MA1FL0099X");
            n.setBankName("工商银行上海分行");
            n.setBankAccount("6222021001009988");
            n.setDefaultCreditDays(45);
            n.setDefaultTaxRate(new BigDecimal("13.00"));
            n.setFinanceCode("ERP-" + code);
            return partners.save(n);
        });
        if (!users.existsByUsername(username)) {
            userService.newUser(username, username, PWD, EnumSet.of(Role.SUPPLIER_PORTAL), p.getId(), false);
        }
        return p.getId();
    }

    private String createApprovedStatement(Long supplierId, String supplierUser, BigDecimal unitPrice, BigDecimal qty) throws Exception {
        String buyer = login("purchase1");
        String fin = login("finance1");
        String sup = login(supplierUser);

        JsonNode po = body(call(buyer, "/api/purchase-orders", Map.of(
                "supplierId", supplierId,
                "lines", List.of(Map.of("itemId", item("RM-GLY-002"), "qty", qty, "unitPrice", unitPrice, "taxRate", 13))
        )).andExpect(status().isOk()));

        long poLineId = po.get("lines").get(0).get("id").asLong();
        JsonNode recv = body(call(buyer, "/api/purchase-orders/" + po.get("id").asLong() + "/receipts", Map.of(
                "lines", List.of(Map.of("poLineId", poLineId, "qty", qty, "extBatchNo", "EXT-FIN-" + po.get("id").asLong(), "warehouseId", warehouseId()))
        )).andExpect(status().isOk()));

        Batch b = batches.findByBatchNo(recv.get("batchNos").get(0).asText()).orElseThrow();
        b.setStatus(BatchStatus.RELEASED);
        batches.save(b);

        JsonNode st = body(call(buyer, "/api/statements", Map.of(
                "supplierId", supplierId,
                "periodTo", d(0).toString()
        )).andExpect(status().isOk()));
        long stId = st.get("id").asLong();
        BigDecimal total = st.get("totalAmount").decimalValue();

        call(sup, "/api/portal/supplier/statements/" + stId + "/confirm", Map.of()).andExpect(status().isOk());
        call(sup, "/api/portal/supplier/statements/" + stId + "/invoice", Map.of(
                "invoiceNo", "INV-" + st.get("statementNo").asText(),
                "invoiceDate", d(0).toString(),
                "amount", total
        )).andExpect(status().isOk());
        call(fin, "/api/statements/" + stId + "/approve", Map.of("password", PWD)).andExpect(status().isOk());
        return st.get("statementNo").asText();
    }

    private ResultActions ack(String statementNo, Map<String, Object> request) throws Exception {
        return mvc.perform(post("/api/integration/finance/vouchers/" + statementNo + "/ack")
                .header("X-Api-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)));
    }

    private ResultActions payCallback(Map<String, Object> request) throws Exception {
        return mvc.perform(post("/api/integration/finance/payments").header("X-Api-Key", API_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)));
    }

    @Test
    void integrationEndpointsRequireApiKeyAndInternalExportRequiresFinanceRole() throws Exception {
        mvc.perform(get("/api/integration/finance/vouchers")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/integration/finance/vouchers").header("X-Api-Key", "wrong")).andExpect(status().isUnauthorized());
        // 内部用户 JWT 不能调用 /api/integration/**
        read(login("finance1"), "/api/integration/finance/vouchers").andExpect(status().isUnauthorized());

        // 内部导出接口仅财务与管理员可用
        read(login("qa1"), "/api/statements/finance-vouchers").andExpect(status().isForbidden());
        read(login("viewer1"), "/api/statements/finance-vouchers").andExpect(status().isForbidden());
        read(login("finance1"), "/api/statements/finance-vouchers").andExpect(status().isOk());
    }

    @Test
    void voucherPullAckAndIdempotentPaymentCallbackWorkEndToEnd() throws Exception {
        Long supplierId = supplierWithFinanceProfile("SUP-FIN-39", "fin_39");
        String stNo = createApprovedStatement(supplierId, "fin_39", new BigDecimal("50.00"), new BigDecimal("2"));
        String otherStNo = createApprovedStatement(supplierId, "fin_39", new BigDecimal("30.00"), new BigDecimal("1"));

        // 1. 外部财务系统拉取待制证应付凭证
        JsonNode pulled = body(mvc.perform(get("/api/integration/finance/vouchers")
                        .header("X-Api-Key", API_KEY)
                        .param("pendingOnly", "true"))
                .andExpect(status().isOk()));
        JsonNode target = null;
        for (JsonNode v : pulled) {
            if (stNo.equals(v.get("statementNo").asText())) {
                target = v;
                break;
            }
        }
        assertThat(target).isNotNull();
        assertThat(target.get("voucherDirection").asText()).isEqualTo("PAYABLE");
        assertThat(target.get("financeCode").asText()).isEqualTo("ERP-SUP-FIN-39");
        assertThat(target.get("taxId").asText()).isEqualTo("91310000MA1FL0099X");
        assertThat(target.get("bankName").asText()).isEqualTo("工商银行上海分行");
        assertThat(target.get("bankAccount").asText()).isEqualTo("6222021001009988");
        assertThat(target.get("totalAmount").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(target.get("financeSyncStatus").asText()).isEqualTo("PENDING");
        assertThat(target.get("taxBreakdown").size()).isEqualTo(1);
        assertThat(target.get("invoices").size()).isEqualTo(1);
        assertThat(target.get("lines").size()).isEqualTo(1);

        // 2. 首次回执失败可以重试，成功后保留首次确认的凭证号、时间和备注。
        ack(stNo, Map.of("success", false, "note", "ERP 科目未映射"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.financeSyncStatus").value("FAILED"))
                .andExpect(jsonPath("$.financeSyncNote").value("ERP 科目未映射"));
        assertThat(body(mvc.perform(get("/api/integration/finance/vouchers").header("X-Api-Key", API_KEY)
                        .param("pendingOnly", "true")).andExpect(status().isOk())).findValuesAsText("statementNo")).contains(stNo);

        ack(stNo, Map.of("success", true, "extVoucherNo", "AP-2026-0039", "note", "已生成应付凭证"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.financeSyncStatus").value("SYNCED"))
                .andExpect(jsonPath("$.extVoucherNo").value("AP-2026-0039"));
        String fin = login("finance1");
        String statementUrl = "/api/statements?statementNo=" + stNo;
        String syncedAt = body(read(fin, statementUrl).andExpect(status().isOk())).get(0).path("financeSyncedAt").asText();
        assertThat(syncedAt).isNotBlank();

        ack(stNo, Map.of("success", true, "extVoucherNo", " AP-2026-0039 ", "note", "重复投递"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.financeSyncStatus").value("SYNCED"))
                .andExpect(jsonPath("$.financeSyncedAt").value(syncedAt))
                .andExpect(jsonPath("$.financeSyncNote").value("已生成应付凭证"));
        ack(stNo, Map.of("success", false, "note", "迟到的失败回执"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.financeSyncStatus").value("SYNCED"))
                .andExpect(jsonPath("$.extVoucherNo").value("AP-2026-0039"));
        ack(stNo, Map.of("success", true, "extVoucherNo", "AP-2026-OTHER"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", containsString("已绑定外部凭证号")));

        JsonNode pendingAfterAck = body(mvc.perform(get("/api/integration/finance/vouchers")
                        .header("X-Api-Key", API_KEY)
                        .param("pendingOnly", "true"))
                .andExpect(status().isOk()));
        assertThat(pendingAfterAck.findValuesAsText("statementNo")).doesNotContain(stNo).contains(otherStNo);

        // 内部结算列表可见外部凭证号
        read(fin, statementUrl).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].financeSyncStatus").value("SYNCED"))
                .andExpect(jsonPath("$[0].extVoucherNo").value("AP-2026-0039"))
                .andExpect(jsonPath("$[0].financeSyncedAt").value(syncedAt))
                .andExpect(jsonPath("$[0].financeSyncNote").value("已生成应付凭证"));

        // 3. 付款水单回调核销（含幂等重复推送与跨单防重）
        Map<String, Object> pay1 = Map.of(
                "statementNo", stNo,
                "extPaymentNo", "BANK-PAY-001",
                "payDate", d(0).toString(),
                "amount", new BigDecimal("60.00"),
                "referenceNo", "REF-BANK-001"
        );
        payCallback(pay1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.paidAmount").value(60.0))
                .andExpect(jsonPath("$.unpaid").value(40.0));

        // 相同 extPaymentNo 重复回调：幂等返回，不重复扣减
        Map<String, Object> retry = new HashMap<>(pay1);
        retry.put("amount", 60);
        retry.put("paymentType", " pay ");
        retry.remove("referenceNo");
        payCallback(retry)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.paidAmount").value(60.0))
                .andExpect(jsonPath("$.unpaid").value(40.0));

        // 同一付款单号的核心字段变更必须报错，不能假装核销成功。
        for (var field : Map.<String, Object>of("amount", 61, "payDate", d(-1).toString(),
                "paymentType", "REFUND", "referenceNo", "REF-OTHER").entrySet()) {
            Map<String, Object> conflicting = new HashMap<>(pay1);
            conflicting.put(field.getKey(), field.getValue());
            payCallback(conflicting).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message", containsString("与已登记的付款水单信息不一致")));
        }
        payCallback(pay1).andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.referenceNo").value("REF-BANK-001"))
                .andExpect(jsonPath("$.paidAmount").value(60.0))
                .andExpect(jsonPath("$.unpaid").value(40.0));

        // 同一 extPaymentNo 不能用于另一张对账单
        mvc.perform(post("/api/integration/finance/payments")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "statementNo", otherStNo,
                                "extPaymentNo", "BANK-PAY-001",
                                "payDate", d(0).toString(),
                                "amount", new BigDecimal("10.00")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("已关联其他对账单")));

        // 尾款回调核销后对账单自动结清（PAID）
        mvc.perform(post("/api/integration/finance/payments")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "statementNo", stNo,
                                "extPaymentNo", "BANK-PAY-002",
                                "payDate", d(0).toString(),
                                "amount", new BigDecimal("40.00")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.unpaid").value(0.0));

        read(login("finance1"), "/api/statements?statementNo=" + stNo)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PAID"))
                .andExpect(jsonPath("$[0].payments.length()").value(2))
                .andExpect(jsonPath("$[0].payments[0].extPaymentNo").value("BANK-PAY-001"))
                .andExpect(jsonPath("$[0].payments[1].extPaymentNo").value("BANK-PAY-002"));

        assertThat(integrationLogs.findTop200ByOrderByIdDesc())
                .anyMatch(l -> "FINANCE".equals(l.getPlatform()) && "付款回调核销".equals(l.getAction()) && l.isSuccess());

        // 4. 财务手工维护外部凭证状态与按同步状态筛选
        long otherId = body(read(login("finance1"), "/api/statements?statementNo=" + otherStNo).andExpect(status().isOk()))
                .get(0).get("id").asLong();
        call(login("purchase1"), "/api/statements/" + otherId + "/finance-sync",
                Map.of("syncStatus", "SYNCED", "extVoucherNo", "AP-MANUAL-1")).andExpect(status().isForbidden());
        call(login("finance1"), "/api/statements/" + otherId + "/finance-sync",
                Map.of("syncStatus", "SYNCED", "extVoucherNo", "AP-MANUAL-1", "note", "手工补录"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.financeSyncStatus").value("SYNCED"))
                .andExpect(jsonPath("$.extVoucherNo").value("AP-MANUAL-1"));
        assertThat(body(read(login("finance1"), "/api/statements?financeSyncStatus=SYNCED").andExpect(status().isOk()))
                .findValuesAsText("statementNo")).contains(otherStNo);
        call(login("finance1"), "/api/statements/" + otherId + "/finance-sync",
                Map.of("syncStatus", "PENDING", "note", "重置重推"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.financeSyncStatus").value("PENDING"))
                .andExpect(jsonPath("$.financeSyncedAt").isEmpty());

        // 5. 集成日志隔离：非价格权限角色看不到 FINANCE 日志（防金额泄露）
        String viewerLogs = read(login("viewer1"), "/api/channel-orders/logs").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(viewerLogs).doesNotContain("BANK-PAY-001").doesNotContain("FINANCE");
        String finLogs = read(login("finance1"), "/api/channel-orders/logs").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(finLogs).contains("BANK-PAY-001");

        // 6. 进项发票台账查询、按月/税率汇总、导出权限与税号核验
        JsonNode ledger = body(read(login("finance1"), "/api/statements/invoice-ledger?supplierId=" + supplierId).andExpect(status().isOk()));
        assertThat(ledger.get("count").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(ledger.get("totalAmount").decimalValue()).isEqualByComparingTo("130.00");
        assertThat(ledger.get("byMonth").size()).isGreaterThanOrEqualTo(1);
        assertThat(ledger.get("byTaxRate").size()).isEqualTo(1);
        read(login("finance1"), "/api/reports/invoice-ledger").andExpect(status().isOk());
        read(login("viewer1"), "/api/reports/invoice-ledger").andExpect(status().isForbidden());
        read(login("qa1"), "/api/statements/invoice-ledger").andExpect(status().isForbidden());

        // 税号不一致拦截测试
        String buyer = login("purchase1");
        String sup = login("fin_39");
        JsonNode po = body(call(buyer, "/api/purchase-orders", Map.of(
                "supplierId", supplierId,
                "lines", List.of(Map.of("itemId", item("RM-GLY-002"), "qty", 1, "unitPrice", 20, "taxRate", 13))
        )).andExpect(status().isOk()));
        JsonNode recv = body(call(buyer, "/api/purchase-orders/" + po.get("id").asLong() + "/receipts", Map.of(
                "lines", List.of(Map.of("poLineId", po.get("lines").get(0).get("id").asLong(), "qty", 1, "extBatchNo", "EXT-TAX-CHK", "warehouseId", warehouseId()))
        )).andExpect(status().isOk()));
        Batch tb = batches.findByBatchNo(recv.get("batchNos").get(0).asText()).orElseThrow();
        tb.setStatus(BatchStatus.RELEASED);
        batches.save(tb);
        long stTaxId = body(call(buyer, "/api/statements", Map.of("supplierId", supplierId, "periodTo", d(0).toString())).andExpect(status().isOk())).get("id").asLong();
        call(sup, "/api/portal/supplier/statements/" + stTaxId + "/confirm", Map.of()).andExpect(status().isOk());
        call(buyer, "/api/statements/" + stTaxId + "/invoices", Map.of("invoices", List.of(Map.of(
                "invoiceNo", "INV-TAX-BAD", "invoiceType", "BLUE", "invoiceDate", d(0).toString(),
                "taxRate", 13, "amount", 20, "sellerTaxId", "91000000000000000X"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("销方税号")));
        call(buyer, "/api/statements/" + stTaxId + "/invoices", Map.of("invoices", List.of(Map.of(
                "invoiceNo", "INV-TAX-OK", "invoiceType", "BLUE", "invoiceDate", d(0).toString(),
                "taxRate", 13, "amount", 20, "sellerTaxId", "91310000MA1FL0099X"))))
                .andExpect(status().isOk());
    }
}
