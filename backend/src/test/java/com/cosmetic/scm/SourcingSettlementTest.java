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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.Role;
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

/** 询价比价和对账结算：封标、隔离、职责分离，金额手算。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SourcingSettlementTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PartnerRepository partners;
    @Autowired ItemRepository items;
    @Autowired WarehouseRepository warehouses;
    @Autowired BatchRepository batches;
    @Autowired UserRepository users;
    @Autowired UserService userService;
    @Autowired TimeProvider time;

    private LocalDate d(int n) {
        return time.today().plusDays(n);
    }

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

    private Long item(String code) {
        return items.findAll().stream().filter(i -> code.equals(i.getCode())).findFirst().orElseThrow().getId();
    }

    /** 新建一家供应商和它的门户账号，金额不受示例数据影响。 */
    private Long supplier(String code, String username) {
        Partner p = partners.findByCode(code).orElseGet(() -> {
            Partner n = new Partner();
            n.setCode(code);
            n.setName(code + " 供应商");
            n.setKind(PartnerKind.SUPPLIER);
            return partners.save(n);
        });
        if (!users.existsByUsername(username)) {
            userService.newUser(username, username, PWD, EnumSet.of(Role.SUPPLIER_PORTAL), p.getId(), false);
        }
        return p.getId();
    }

    private Map<String, Object> quote(long lineId, String price) {
        return Map.of("rfqLineId", lineId, "unitPrice", new BigDecimal(price), "taxRate", new BigDecimal("13"), "leadDays", 5, "validUntil", d(30).toString());
    }

    @Test
    void quotesStaySealedUntilOpenAndOnlyBuyersAndFinanceSeePrices() throws Exception {
        Long a = supplier("SUP-RFQ-A", "rfq_a");
        Long b = supplier("SUP-RFQ-B", "rfq_b");
        Long c = supplier("SUP-RFQ-C", "rfq_c");
        String buyer = login("purchase1");
        JsonNode rfq = body(call(buyer, "/api/rfqs", Map.of("title", "甘油询价", "deadline", d(7).toString(), "supplierIds", List.of(a, b),
                "lines", List.of(Map.of("itemId", item("RM-GLY-002"), "qty", 100)))).andExpect(status().isOk()));
        long id = rfq.get("id").asLong();
        long line = rfq.get("lines").get(0).get("id").asLong();

        // 其它内部岗位进不来
        for (String u : List.of("viewer1", "qa1", "prod1", "sales1")) {
            read(login(u), "/api/rfqs").andExpect(status().isForbidden());
            read(login(u), "/api/statements").andExpect(status().isForbidden());
        }
        read(login("viewer1"), "/api/reports/statements").andExpect(status().isForbidden());

        // 没被邀请的看不到也报不了
        String sc = login("rfq_c");
        assertThat(body(read(sc, "/api/portal/supplier/rfqs")).size()).isEqualTo(0);
        call(sc, "/api/portal/supplier/rfqs/" + id + "/quote", Map.of("lines", List.of(quote(line, "1")))).andExpect(status().isNotFound());

        String sa = login("rfq_a");
        String sb = login("rfq_b");
        call(sa, "/api/portal/supplier/rfqs/" + id + "/quote", Map.of("lines", List.of(quote(line, "87654.3")))).andExpect(status().isOk());

        // 开标前内部拿不到价格：接口不返回，审计也不记
        String sealed = text(read(buyer, "/api/rfqs").andExpect(status().isOk()));
        assertThat(sealed).doesNotContain("87654.3");
        assertThat(text(read(login("admin", "Admin@123456"), "/api/audit?limit=300"))).doesNotContain("87654.3");
        // 还有人没报，不能提前开标
        call(buyer, "/api/rfqs/" + id + "/open", Map.of()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("还没报完")));

        // 截止前可以改价
        call(sa, "/api/portal/supplier/rfqs/" + id + "/quote", Map.of("lines", List.of(quote(line, "12")))).andExpect(status().isOk());
        // 有效期不能早于截止日期
        var early = new HashMap<>(quote(line, "12"));
        early.put("validUntil", d(1).toString());
        call(sa, "/api/portal/supplier/rfqs/" + id + "/quote", Map.of("lines", List.of(early))).andExpect(status().isBadRequest());
        call(sb, "/api/portal/supplier/rfqs/" + id + "/quote", Map.of("lines", List.of(quote(line, "10")))).andExpect(status().isOk());
        // 看不到别家的报价
        String seenByA = text(read(sa, "/api/portal/supplier/rfqs"));
        assertThat(seenByA).contains("12").doesNotContain("SUP-RFQ-B").doesNotContain("rfq_b");
        assertThat(body(read(sa, "/api/portal/supplier/rfqs")).get(0).get("lines").get(0).get("myQuote").get("unitPrice").decimalValue()).isEqualByComparingTo("12");

        // 都报完可以提前开标，开标后不能再报
        JsonNode opened = body(call(buyer, "/api/rfqs/" + id + "/open", Map.of()).andExpect(status().isOk()));
        assertThat(opened.get("sealed").asBoolean()).isFalse();
        JsonNode qs = opened.get("lines").get(0).get("quotes");
        assertThat(qs.size()).isEqualTo(2);
        assertThat(qs.get(0).get("supplierId").asLong()).isEqualTo(b); // 按价格排序
        assertThat(qs.get(0).get("lowest").asBoolean()).isTrue();
        assertThat(qs.get(1).get("amount").decimalValue()).isEqualByComparingTo("1200");
        call(sa, "/api/portal/supplier/rfqs/" + id + "/quote", Map.of("lines", List.of(quote(line, "1")))).andExpect(status().isBadRequest());

        // 定标人不能是询价人
        Map<String, Object> pickB = new HashMap<>(Map.of("lines", List.of(Map.of("rfqLineId", line, "supplierId", b)), "password", PWD, "reason", "只有两家"));
        call(buyer, "/api/rfqs/" + id + "/award", pickB).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不能是询价人")));
        String admin = login("admin", "Admin@123456");
        // 签批密码、缺原因、没报的供应商
        call(admin, "/api/rfqs/" + id + "/award", Map.of("lines", List.of(Map.of("rfqLineId", line, "supplierId", b)), "password", "wrong", "reason", "x")).andExpect(status().isBadRequest());
        call(admin, "/api/rfqs/" + id + "/award", Map.of("lines", List.of(Map.of("rfqLineId", line, "supplierId", b)), "password", "Admin@123456"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("定标原因")));
        call(admin, "/api/rfqs/" + id + "/award", Map.of("lines", List.of(Map.of("rfqLineId", line, "supplierId", c)), "password", "Admin@123456", "reason", "x")).andExpect(status().isBadRequest());
        // 定标生成带价格的采购单
        JsonNode awarded = body(call(admin, "/api/rfqs/" + id + "/award", Map.of("lines", List.of(Map.of("rfqLineId", line, "supplierId", b)), "password", "Admin@123456", "reason", "只有两家")).andExpect(status().isOk()));
        assertThat(awarded.get("status").asText()).isEqualTo("AWARDED");
        String poNo = awarded.get("lines").get(0).get("quotes").get(0).get("poNo").asText();
        assertThat(poNo).startsWith("PO-");
        call(admin, "/api/rfqs/" + id + "/award", Map.of("lines", List.of(Map.of("rfqLineId", line, "supplierId", b)), "password", "Admin@123456", "reason", "x")).andExpect(status().isBadRequest());

        // 单价只给采购和财务：查询接口按角色隐藏，供应商看自己的
        JsonNode buyerPo = poByNo(buyer, poNo);
        assertThat(buyerPo.get("lines").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("10");
        assertThat(poByNo(login("finance1"), poNo).get("lines").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("10");
        assertThat(poByNo(login("qa1"), poNo).get("lines").get(0).get("unitPrice").isNull()).isTrue();
        assertThat(poByNo(login("viewer1"), poNo).get("lines").get(0).get("unitPrice").isNull()).isTrue();

        // 结果：中标的知道，没中的只知道没中
        assertThat(body(read(sb, "/api/portal/supplier/rfqs")).get(0).get("stateLabel").asText()).isEqualTo("已中标");
        assertThat(body(read(sa, "/api/portal/supplier/rfqs")).get(0).get("stateLabel").asText()).isEqualTo("未中标");
        assertThat(text(read(sa, "/api/portal/supplier/rfqs"))).doesNotContain(poNo).doesNotContain("只有两家");
    }

    private JsonNode poByNo(String token, String poNo) throws Exception {
        for (JsonNode p : body(read(token, "/api/purchase-orders").andExpect(status().isOk()))) {
            if (poNo.equals(p.get("poNo").asText())) {
                return p;
            }
        }
        throw new AssertionError(poNo);
    }

    @Test
    void cannotInviteUnqualifiedSupplierOrBannedItem() throws Exception {
        Long a = supplier("SUP-RFQ-BAD", "rfq_bad");
        Partner p = partners.findById(a).orElseThrow();
        p.setQualified(false);
        partners.save(p);
        String buyer = login("purchase1");
        call(buyer, "/api/rfqs", Map.of("title", "x", "deadline", d(1).toString(), "supplierIds", List.of(a), "lines", List.of(Map.of("itemId", item("RM-GLY-002"), "qty", 1))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不在合格名录")));
        p.setQualified(true);
        partners.save(p);
        call(buyer, "/api/rfqs", Map.of("title", "x", "deadline", d(-1).toString(), "supplierIds", List.of(a), "lines", List.of(Map.of("itemId", item("RM-GLY-002"), "qty", 1))))
                .andExpect(status().isBadRequest());
    }

    /** 一张采购单收货一批，放行后才能结算。返回批次。 */
    private List<Batch> buyAndReceive(String buyer, Long supplier, String... codeQtyPriceTax) throws Exception {
        List<Map<String, Object>> ls = new ArrayList<>();
        for (int i = 0; i < codeQtyPriceTax.length; i += 4) {
            Map<String, Object> l = new HashMap<>(Map.of("itemId", item(codeQtyPriceTax[i]), "qty", new BigDecimal(codeQtyPriceTax[i + 1])));
            if (!codeQtyPriceTax[i + 2].isEmpty()) {
                l.put("unitPrice", new BigDecimal(codeQtyPriceTax[i + 2]));
                l.put("taxRate", new BigDecimal(codeQtyPriceTax[i + 3]));
            }
            ls.add(l);
        }
        JsonNode po = body(call(buyer, "/api/purchase-orders", Map.of("supplierId", supplier, "lines", ls)).andExpect(status().isOk()));
        long wh = warehouses.findAll().stream().filter(w -> "WH-MAIN".equals(w.getCode())).findFirst().orElseThrow().getId();
        List<Map<String, Object>> recv = new ArrayList<>();
        for (JsonNode l : po.get("lines")) {
            recv.add(Map.of("poLineId", l.get("id").asLong(), "qty", l.get("qty").decimalValue(), "extBatchNo", "EXT-" + l.get("id").asLong(), "warehouseId", wh));
        }
        JsonNode r = body(call(buyer, "/api/purchase-orders/" + po.get("id").asLong() + "/receipts", Map.of("lines", recv)).andExpect(status().isOk()));
        List<Batch> out = new ArrayList<>();
        for (JsonNode no : r.get("batchNos")) {
            out.add(batches.findByBatchNo(no.asText()).orElseThrow());
        }
        return out;
    }

    private void setStatus(Batch b, BatchStatus s) {
        b.setStatus(s);
        batches.save(b);
    }

    /** 金额手算：10 × 11.3 = 113.00，税 13%：113 × 13 / 113 = 13.00；3 × 33.3333 = 100.00（99.9999 进位），税 6%：100 × 6 / 106 = 5.66。 */
    @Test
    void threeWayMatchWithHandCalculatedAmounts() throws Exception {
        Long s = supplier("SUP-SET-A", "set_a");
        supplier("SUP-SET-B", "set_b");
        String buyer = login("purchase1");
        List<Batch> b1 = buyAndReceive(buyer, s, "RM-GLY-002", "10", "11.3", "13", "RM-NIA-001", "3", "33.3333", "6");
        setStatus(b1.get(0), BatchStatus.RELEASED);
        setStatus(b1.get(1), BatchStatus.RELEASED);
        // 不结算：待检、不合格、冻结；没单价的单独列出来
        List<Batch> quarantine = buyAndReceive(buyer, s, "RM-GLY-002", "7", "100", "13");
        List<Batch> rejected = buyAndReceive(buyer, s, "RM-GLY-002", "8", "100", "13");
        setStatus(rejected.get(0), BatchStatus.REJECTED);
        List<Batch> frozen = buyAndReceive(buyer, s, "RM-GLY-002", "9", "100", "13");
        setStatus(frozen.get(0), BatchStatus.FROZEN);
        List<Batch> noPrice = buyAndReceive(buyer, s, "RM-GLY-002", "5", "", "");
        setStatus(noPrice.get(0), BatchStatus.RELEASED);

        JsonNode pv = body(read(buyer, "/api/statements/preview?supplierId=" + s + "&periodTo=" + d(0)).andExpect(status().isOk()));
        assertThat(pv.get("lines").size()).isEqualTo(2);
        assertThat(pv.get("goodsAmount").decimalValue()).isEqualByComparingTo("213.00");
        assertThat(pv.get("taxAmount").decimalValue()).isEqualByComparingTo("18.66");
        assertThat(pv.get("missingPrice").size()).isEqualTo(1);
        assertThat(pv.get("missingPrice").get(0).get("batchNo").asText()).isEqualTo(noPrice.get(0).getBatchNo());
        // 截止日期之前入库的才算
        assertThat(body(read(buyer, "/api/statements/preview?supplierId=" + s + "&periodTo=" + d(-1))).get("lines").size()).isEqualTo(0);

        // 调整要有原因，合计 = 213 - 13 = 200
        call(buyer, "/api/statements", Map.of("supplierId", s, "periodTo", d(0).toString(), "adjustment", -13)).andExpect(status().isBadRequest());
        JsonNode st = body(call(buyer, "/api/statements", Map.of("supplierId", s, "periodTo", d(0).toString(), "adjustment", -13, "adjustmentNote", "包装破损扣款")).andExpect(status().isOk()));
        long id = st.get("id").asLong();
        assertThat(st.get("totalAmount").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(st.get("status").asText()).isEqualTo("SENT");
        // 同一批次不能再结算
        call(buyer, "/api/statements", Map.of("supplierId", s, "periodTo", d(0).toString())).andExpect(status().isBadRequest());
        assertThat(body(read(buyer, "/api/statements/preview?supplierId=" + s + "&periodTo=" + d(0))).get("lines").size()).isEqualTo(0);
        // 进了对账单的单价不能改
        long poId = poByNo(buyer, b1.get(0).getSourceNo()).get("id").asLong();
        long poLine = poByNo(buyer, b1.get(0).getSourceNo()).get("lines").get(0).get("id").asLong();
        call(buyer, "/api/purchase-orders/" + poId + "/prices", Map.of("reason", "改价", "lines", List.of(Map.of("poLineId", poLine, "unitPrice", 1, "taxRate", 13)))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("已进了对账单")));

        // 别家看不到也动不了
        String other = login("set_b");
        assertThat(body(read(other, "/api/portal/supplier/statements")).size()).isEqualTo(0);
        call(other, "/api/portal/supplier/statements/" + id + "/confirm", Map.of()).andExpect(status().isNotFound());
        read(other, "/api/portal/supplier/statements/" + id + "/attachments").andExpect(status().isNotFound());

        String sup = login("set_a");
        assertThat(body(read(sup, "/api/portal/supplier/statements")).get(0).get("adjustmentNote").asText()).isEqualTo("包装破损扣款");
        Map<String, Object> inv = new HashMap<>(Map.of("invoiceNo", "INV-1", "invoiceDate", d(0).toString(), "amount", new BigDecimal("200.00")));
        // 没确认不能开票
        call(sup, "/api/portal/supplier/statements/" + id + "/invoice", inv).andExpect(status().isBadRequest());
        call(sup, "/api/portal/supplier/statements/" + id + "/confirm", Map.of()).andExpect(status().isOk());
        call(sup, "/api/portal/supplier/statements/" + id + "/confirm", Map.of()).andExpect(status().isBadRequest());
        // 发票对不上不行
        var wrong = new HashMap<>(inv);
        wrong.put("amount", new BigDecimal("200.01"));
        call(sup, "/api/portal/supplier/statements/" + id + "/invoice", wrong).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不一致")));
        var future = new HashMap<>(inv);
        future.put("invoiceDate", d(1).toString());
        call(sup, "/api/portal/supplier/statements/" + id + "/invoice", future).andExpect(status().isBadRequest());
        call(sup, "/api/portal/supplier/statements/" + id + "/invoice", inv).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INVOICED"));

        // 采购不能审批；审批人不能是制单人
        call(buyer, "/api/statements/" + id + "/approve", Map.of("password", PWD)).andExpect(status().isForbidden());
        String fin = login("finance1");
        call(fin, "/api/statements/" + id + "/pay", Map.of("payDate", d(0).toString(), "amount", 1)).andExpect(status().isBadRequest());
        call(fin, "/api/statements/" + id + "/approve", Map.of("password", "wrong")).andExpect(status().isBadRequest());
        assertThat(text(read(fin, "/api/dashboard"))).contains("对账单待审批");
        call(fin, "/api/statements/" + id + "/approve", Map.of("password", PWD)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        // 审批后不能作废
        call(buyer, "/api/statements/" + id + "/cancel", Map.of("reason", "x")).andExpect(status().isBadRequest());

        // 分批付款，不能超付
        call(fin, "/api/statements/" + id + "/pay", Map.of("payDate", d(0).toString(), "amount", new BigDecimal("150"), "referenceNo", "BANK-1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.unpaid").value(50.00));
        call(fin, "/api/statements/" + id + "/pay", Map.of("payDate", d(0).toString(), "amount", new BigDecimal("50.01"))).andExpect(status().isBadRequest());
        call(fin, "/api/statements/" + id + "/pay", Map.of("payDate", d(0).toString(), "amount", new BigDecimal("50"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID")).andExpect(jsonPath("$.payments.length()").value(2));
        // 导出
        read(fin, "/api/reports/statements").andExpect(status().isOk());
        assertThat(quarantine).hasSize(1);
    }

    @Test
    void disputeThenVoidReleasesBatchesAndApproverCannotBeTheMaker() throws Exception {
        Long s = supplier("SUP-SET-C", "set_c");
        String admin = login("admin", "Admin@123456");
        List<Batch> b = buyAndReceive(admin, s, "RM-GLY-002", "2", "50", "0");
        setStatus(b.get(0), BatchStatus.RELEASED);
        JsonNode st = body(call(admin, "/api/statements", Map.of("supplierId", s, "periodTo", d(0).toString())).andExpect(status().isOk()));
        long id = st.get("id").asLong();
        String sup = login("set_c");
        call(sup, "/api/portal/supplier/statements/" + id + "/dispute", Map.of()).andExpect(status().isBadRequest());
        call(sup, "/api/portal/supplier/statements/" + id + "/dispute", Map.of("note", "数量不对")).andExpect(status().isOk());
        assertThat(text(read(login("purchase1"), "/api/dashboard"))).contains("对账单有异议").contains("数量不对");
        // 有异议不能确认；作废后批次放出来，可以重新生成
        call(sup, "/api/portal/supplier/statements/" + id + "/confirm", Map.of()).andExpect(status().isBadRequest());
        call(admin, "/api/statements/" + id + "/cancel", Map.of("reason", "重新核对")).andExpect(status().isOk());
        JsonNode again = body(call(admin, "/api/statements", Map.of("supplierId", s, "periodTo", d(0).toString())).andExpect(status().isOk()));
        long id2 = again.get("id").asLong();
        assertThat(again.get("totalAmount").decimalValue()).isEqualByComparingTo("100");
        // 作废的发票号可以再用：这里走到审批
        call(sup, "/api/portal/supplier/statements/" + id2 + "/confirm", Map.of()).andExpect(status().isOk());
        call(admin, "/api/statements/" + id2 + "/invoice", Map.of("invoiceNo", "INV-C", "invoiceDate", d(0).toString(), "amount", 100)).andExpect(status().isOk());
        call(admin, "/api/statements/" + id2 + "/approve", Map.of("password", "Admin@123456")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("不能是制单人")));
        call(login("finance1"), "/api/statements/" + id2 + "/approve", Map.of("password", PWD)).andExpect(status().isOk());
    }

    @Test
    void changingAnExistingPriceNeedsAReason() throws Exception {
        Long s = supplier("SUP-SET-D", "set_d");
        String buyer = login("purchase1");
        JsonNode po = body(call(buyer, "/api/purchase-orders", Map.of("supplierId", s, "lines", List.of(Map.of("itemId", item("RM-GLY-002"), "qty", 1)))).andExpect(status().isOk()));
        long id = po.get("id").asLong();
        long line = po.get("lines").get(0).get("id").asLong();
        call(buyer, "/api/purchase-orders/" + id + "/prices", Map.of("lines", List.of(Map.of("poLineId", line, "unitPrice", 9, "taxRate", 13)))).andExpect(status().isOk());
        call(buyer, "/api/purchase-orders/" + id + "/prices", Map.of("lines", List.of(Map.of("poLineId", line, "unitPrice", 8, "taxRate", 13)))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("写明原因")));
        call(buyer, "/api/purchase-orders/" + id + "/prices", Map.of("reason", "谈判降价", "lines", List.of(Map.of("poLineId", line, "unitPrice", 8, "taxRate", 13)))).andExpect(status().isOk());
        read(login("qa1"), "/api/purchase-orders").andExpect(status().isOk());
        call(login("qa1"), "/api/purchase-orders/" + id + "/prices", Map.of("lines", List.of(Map.of("poLineId", line, "unitPrice", 1, "taxRate", 13)))).andExpect(status().isForbidden());
    }
}
