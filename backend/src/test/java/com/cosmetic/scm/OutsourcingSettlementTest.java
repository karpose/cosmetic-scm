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
import java.util.List;
import java.util.Map;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.MoStatus;
import com.cosmetic.scm.domain.MoType;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.SourceType;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.ItemRepository;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.PartnerRepository;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 委外加工费对账结算：加工费维护、权限隔离、委外对账单生成/确认/开票/审批与外部财务凭证导出。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-outsourcing-settlement;MODE=MySQL;DB_CLOSE_DELAY=-1")
class OutsourcingSettlementTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;
    private static final String API_KEY = "test-integration-key";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PartnerRepository partners;
    @Autowired ItemRepository items;
    @Autowired BatchRepository batches;
    @Autowired MfgOrderRepository mfgOrders;
    @Autowired com.cosmetic.scm.security.UserRepository users;
    @Autowired com.cosmetic.scm.security.UserService userService;
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

    @Test
    void outsourcingProcessingFeeFlowsIntoStatementAndApVoucher() throws Exception {
        Partner oem = partners.findByCode("OEM-SET-41").orElseGet(() -> {
            Partner n = new Partner();
            n.setCode("OEM-SET-41");
            n.setName("委外对账测试工厂");
            n.setKind(PartnerKind.OEM);
            n.setQualified(true);
            n.setTaxId("91440100MA590041XX");
            n.setDefaultCreditDays(45);
            n.setDefaultTaxRate(new BigDecimal("6.00"));
            n.setFinanceCode("ERP-OEM-041");
            return partners.save(n);
        });
        Item fg = items.findAll().stream().filter(i -> i.getItemType() == com.cosmetic.scm.domain.ItemType.FG).findFirst().orElseThrow();

        // 构造一张已完工且成品已上市放行的委外加工单
        Batch outBatch = new Batch();
        outBatch.setBatchNo("FG-OEM-41-001");
        outBatch.setItemId(fg.getId());
        outBatch.setSourceType(SourceType.MO);
        outBatch.setSourceNo("MO-OEM-41-001");
        outBatch.setPartnerId(oem.getId());
        outBatch.setExtBatchNo("OEM-LOT-41");
        outBatch.setQtyIn(new BigDecimal("100"));
        outBatch.setStatus(BatchStatus.RELEASED);
        outBatch.setCreatedAt(time.now());
        batches.save(outBatch);

        MfgOrder mo = new MfgOrder();
        mo.setMoNo("MO-OEM-41-001");
        mo.setMoType(MoType.OEM);
        mo.setFactoryId(oem.getId());
        mo.setFgItemId(fg.getId());
        mo.setPlannedQty(new BigDecimal("100"));
        mo.setActualQty(new BigDecimal("100"));
        mo.setPlannedDate(d(0));
        mo.setStatus(MoStatus.COMPLETED);
        mo.setOutBatchId(outBatch.getId());
        mo.setCreatedBy("prod1");
        mo.setCreatedAt(time.now());
        mo.setCompletedAt(time.now());
        mfgOrders.save(mo);

        String buyer = login("purchase1");
        String finance = login("finance1");
        String qa = login("qa1");

        // 1. 未设加工费前，预览显示 missingPrice
        JsonNode previewMissing = body(read(buyer, "/api/statements/preview?supplierId=" + oem.getId() + "&periodTo=" + d(0))
                .andExpect(status().isOk()));
        assertThat(previewMissing.get("missingPrice").size()).isEqualTo(1);
        assertThat(previewMissing.get("lines").size()).isEqualTo(0);

        // 2. 只有采购/财务/管理员可设置加工费，其它角色返回 403 且列表不返回费用
        call(qa, "/api/mfg-orders/" + mo.getId() + "/processing-fee",
                Map.of("processingUnitFee", new BigDecimal("10.60"), "processingTaxRate", new BigDecimal("6.00")))
                .andExpect(status().isForbidden());
        call(buyer, "/api/mfg-orders/" + mo.getId() + "/processing-fee",
                Map.of("processingUnitFee", new BigDecimal("10.60")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processingUnitFee").value(10.6))
                .andExpect(jsonPath("$.processingTaxRate").value(6.0));

        // 非价格权限角色查询生产单时看不到加工费
        String qaMoText = read(qa, "/api/mfg-orders").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(qaMoText).doesNotContain("10.6");

        // 3. 生成委外加工费对账单：100 × 10.60 = 1060.00，税率 6%，税额 = 60.00，默认账期 45 天
        JsonNode st = body(call(buyer, "/api/statements", Map.of(
                "supplierId", oem.getId(),
                "periodTo", d(0).toString()
        )).andExpect(status().isOk()));
        long stId = st.get("id").asLong();
        String stNo = st.get("statementNo").asText();
        assertThat(st.get("statementType").asText()).isEqualTo("OUTSOURCING");
        assertThat(st.get("statementTypeLabel").asText()).isEqualTo("委外加工费");
        assertThat(st.get("creditDays").asInt()).isEqualTo(45);
        assertThat(st.get("totalAmount").decimalValue()).isEqualByComparingTo("1060.00");
        assertThat(st.get("taxAmount").decimalValue()).isEqualByComparingTo("60.00");

        // 已进对账单的委外加工单不能再改加工费
        call(buyer, "/api/mfg-orders/" + mo.getId() + "/processing-fee",
                Map.of("processingUnitFee", new BigDecimal("12.00"), "reason", "调价"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("已进了对账单")));

        // 4. 委外对账单支持内部确认 -> 代登记发票 -> 财务审批 -> 外部财务拉取应付凭证
        call(buyer, "/api/statements/" + stId + "/confirm", Map.of()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        call(buyer, "/api/statements/" + stId + "/invoices", Map.of("invoices", List.of(Map.of(
                "invoiceNo", "INV-OEM-41", "invoiceType", "BLUE", "invoiceDate", d(0).toString(),
                "taxRate", 6, "amount", 1060, "sellerTaxId", "91440100MA590041XX"))))
                .andExpect(status().isOk());
        call(finance, "/api/statements/" + stId + "/approve", Map.of("password", PWD)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        JsonNode vouchers = body(mvc.perform(get("/api/integration/finance/vouchers")
                        .header("X-Api-Key", API_KEY)
                        .param("pendingOnly", "true"))
                .andExpect(status().isOk()));
        JsonNode oemVoucher = null;
        for (JsonNode v : vouchers) {
            if (stNo.equals(v.get("statementNo").asText())) {
                oemVoucher = v;
                break;
            }
        }
        assertThat(oemVoucher).isNotNull();
        assertThat(oemVoucher.get("statementType").asText()).isEqualTo("OUTSOURCING");
        assertThat(oemVoucher.get("financeCode").asText()).isEqualTo("ERP-OEM-041");
        assertThat(oemVoucher.get("totalAmount").decimalValue()).isEqualByComparingTo("1060.00");

        // 5. 受托厂门户（OEM Portal）在线确认、登记发票与跨厂隔离
        if (!users.existsByUsername("oem_portal_41")) {
            userService.newUser("oem_portal_41", "oem_portal_41", PWD, java.util.EnumSet.of(com.cosmetic.scm.domain.Role.OEM_PORTAL), oem.getId(), false);
        }
        Partner otherOem = partners.findByCode("OEM-SET-41-B").orElseGet(() -> {
            Partner n = new Partner();
            n.setCode("OEM-SET-41-B");
            n.setName("其他委外厂");
            n.setKind(PartnerKind.OEM);
            n.setQualified(true);
            return partners.save(n);
        });
        if (!users.existsByUsername("oem_portal_41_b")) {
            userService.newUser("oem_portal_41_b", "oem_portal_41_b", PWD, java.util.EnumSet.of(com.cosmetic.scm.domain.Role.OEM_PORTAL), otherOem.getId(), false);
        }
        String oemToken = login("oem_portal_41");
        String otherOemToken = login("oem_portal_41_b");
        assertThat(body(read(oemToken, "/api/portal/oem/statements").andExpect(status().isOk())).findValuesAsText("statementNo")).contains(stNo);
        assertThat(body(read(otherOemToken, "/api/portal/oem/statements").andExpect(status().isOk())).findValuesAsText("statementNo")).doesNotContain(stNo);
        call(otherOemToken, "/api/portal/oem/statements/" + stId + "/confirm", Map.of()).andExpect(status().isNotFound());
    }
}
