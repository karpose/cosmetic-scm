package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.ItemRepository;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.PartnerRepository;
import com.cosmetic.scm.master.WarehouseRepository;
import com.cosmetic.scm.sales.SalesOrder;
import com.cosmetic.scm.sales.SalesOrderRepository;
import com.cosmetic.scm.sales.SoLine;
import com.cosmetic.scm.sales.SoLineRepository;
import com.cosmetic.scm.salesreturn.SalesReturn;
import com.cosmetic.scm.salesreturn.SalesReturnLine;
import com.cosmetic.scm.salesreturn.SalesReturnLineRepository;
import com.cosmetic.scm.salesreturn.SalesReturnRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** V44 客户退货冲减应收、AR 作废重算、销项台账、客户授信概览与生产批次实际成本核算集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class V44ArCostingTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PartnerRepository partners;
    @Autowired ItemRepository items;
    @Autowired WarehouseRepository warehouses;
    @Autowired SalesOrderRepository salesOrders;
    @Autowired SoLineRepository soLines;
    @Autowired SalesReturnRepository salesReturns;
    @Autowired SalesReturnLineRepository salesReturnLines;
    @Autowired TimeProvider time;

    private String login(String u) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", u, "password", PWD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void arReturnOffsetCancelLedgerCreditAndBatchCostingWork() throws Exception {
        Partner customer = partners.findByCode("CUS-V44").orElseGet(() -> {
            Partner p = new Partner();
            p.setCode("CUS-V44");
            p.setName("V44退货冲减测试客户");
            p.setKind(PartnerKind.CUSTOMER);
            p.setCreditLimit(new BigDecimal("10000.00"));
            p.setDefaultTaxRate(new BigDecimal("13.00"));
            return partners.save(p);
        });
        Item fg = items.findAll().stream().filter(i -> i.getItemType() == ItemType.FG).findFirst().orElseThrow();
        long whId = warehouses.findAll().stream().filter(w -> "WH-MAIN".equals(w.getCode())).findFirst().orElseThrow().getId();

        SalesOrder so = new SalesOrder();
        so.setSoNo("SO-V44-001");
        so.setCustomerId(customer.getId());
        so.setWarehouseId(whId);
        so.setOrderDate(time.today());
        so.setStatus("SHIPPED");
        so.setCreatedBy("sales1");
        so.setCreatedAt(time.now());
        salesOrders.save(so);

        SoLine sol = new SoLine();
        sol.setSoId(so.getId());
        sol.setItemId(fg.getId());
        sol.setQty(new BigDecimal("10"));
        sol.setUnitPrice(new BigDecimal("100.00"));
        sol.setTaxRate(new BigDecimal("13.00"));
        soLines.save(sol);

        SalesReturn sr = new SalesReturn();
        sr.setReturnNo("SR-V44-001");
        sr.setCustomerId(customer.getId());
        sr.setWarehouseId(whId);
        sr.setReturnDate(time.today());
        sr.setReason("外箱破损退回");
        sr.setCreatedBy("sales1");
        sr.setCreatedAt(time.now());
        salesReturns.save(sr);

        SalesReturnLine srl = new SalesReturnLine();
        srl.setReturnId(sr.getId());
        srl.setItemId(fg.getId());
        srl.setQty(new BigDecimal("2"));
        salesReturnLines.save(srl);

        String sales = login("sales1");
        String fin = login("finance1");
        String qa = login("qa1");

        // 10 * 100 - 2 * 100 = 800
        String body1 = mvc.perform(post("/api/ar-statements").header("Authorization", "Bearer " + sales)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("customerId", customer.getId(), "periodTo", time.today().toString()))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode ar1 = json.readTree(body1);
        assertThat(ar1.get("totalAmount").decimalValue()).isEqualByComparingTo("800.00");
        long ar1Id = ar1.get("id").asLong();

        // 作废后释放出库行与退货行，可重新生成
        mvc.perform(post("/api/ar-statements/" + ar1Id + "/cancel").header("Authorization", "Bearer " + fin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("reason", "重算测试"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mvc.perform(post("/api/ar-statements").header("Authorization", "Bearer " + sales)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("customerId", customer.getId(), "periodTo", time.today().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(800.0));

        // 销项发票台账与授信概览 + Excel 导出
        mvc.perform(get("/api/ar-statements/invoice-ledger").header("Authorization", "Bearer " + fin)).andExpect(status().isOk());
        mvc.perform(get("/api/ar-statements/credit-summary").header("Authorization", "Bearer " + fin)).andExpect(status().isOk());
        mvc.perform(get("/api/reports/ar-invoice-ledger").header("Authorization", "Bearer " + fin)).andExpect(status().isOk());
        mvc.perform(get("/api/reports/customer-credit").header("Authorization", "Bearer " + fin)).andExpect(status().isOk());

        // 生产批次实际成本核算权限隔离
        mvc.perform(get("/api/analytics/batch-costs").header("Authorization", "Bearer " + fin)).andExpect(status().isOk());
        mvc.perform(get("/api/analytics/batch-costs").header("Authorization", "Bearer " + qa)).andExpect(status().isForbidden());
        mvc.perform(get("/api/reports/batch-costs").header("Authorization", "Bearer " + fin)).andExpect(status().isOk());
        mvc.perform(get("/api/reports/batch-costs").header("Authorization", "Bearer " + qa)).andExpect(status().isForbidden());
    }
}
