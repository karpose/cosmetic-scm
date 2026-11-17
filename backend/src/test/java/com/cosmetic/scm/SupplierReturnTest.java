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
import java.util.List;
import java.util.Map;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.Role;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.inventory.StockRepository;
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

/** 退货给供应商：质量签批才扣库存；结算前退减数量，结算后退冲减，作废后能还原，都不重复算。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SupplierReturnTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PartnerRepository partners;
    @Autowired ItemRepository items;
    @Autowired WarehouseRepository warehouses;
    @Autowired BatchRepository batches;
    @Autowired StockRepository stocks;
    @Autowired UserRepository users;
    @Autowired UserService userService;
    @Autowired TimeProvider time;

    private LocalDate today() {
        return time.today();
    }

    private String login(String u) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", u, "password", PWD)))).andExpect(status().isOk())
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

    private Long wh() {
        return warehouses.findAll().stream().filter(w -> "WH-MAIN".equals(w.getCode())).findFirst().orElseThrow().getId();
    }

    private Batch receive(String buyer, Long supplier, String qty, String price, String tax, boolean release) throws Exception {
        Long item = items.findAll().stream().filter(i -> "RM-GLY-002".equals(i.getCode())).findFirst().orElseThrow().getId();
        JsonNode po = body(call(buyer, "/api/purchase-orders", Map.of("supplierId", supplier,
                "lines", List.of(Map.of("itemId", item, "qty", new BigDecimal(qty), "unitPrice", new BigDecimal(price), "taxRate", new BigDecimal(tax))))).andExpect(status().isOk()));
        JsonNode r = body(call(buyer, "/api/purchase-orders/" + po.get("id").asLong() + "/receipts", Map.of("lines", List.of(Map.of(
                "poLineId", po.get("lines").get(0).get("id").asLong(), "qty", new BigDecimal(qty), "extBatchNo", "EXT-" + System.nanoTime(), "warehouseId", wh())))).andExpect(status().isOk()));
        Batch b = batches.findByBatchNo(r.get("batchNos").get(0).asText()).orElseThrow();
        if (release) {
            b.setStatus(BatchStatus.RELEASED);
            batches.save(b);
        }
        return b;
    }

    private long createReturn(String maker, Batch b, String qty) throws Exception {
        return body(call(maker, "/api/supplier-returns", Map.of("batchId", b.getId(), "warehouseId", wh(), "qty", new BigDecimal(qty), "reason", "来料破损")).andExpect(status().isOk())).get("id").asLong();
    }

    private void returnDone(String maker, Batch b, String qty) throws Exception {
        long id = createReturn(maker, b, qty);
        call(login("qa1"), "/api/supplier-returns/" + id + "/approve", Map.of("password", PWD)).andExpect(status().isOk());
    }

    private BigDecimal stockOf(Batch b) {
        return stocks.findByBatchId(b.getId()).stream().map(s -> s.getQty()).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void qualitySignsThenStockGoesDownAndItShowsInTrace() throws Exception {
        Long s = supplier("SUP-RS-A", "rs_a");
        supplier("SUP-RS-B", "rs_b");
        String buyer = login("purchase1");
        // 待检的也能退：来料检验没过就退回去是最常见的情况
        Batch b = receive(buyer, s, "10", "1", "0", false);
        assertThat(b.getStatus()).isEqualTo(BatchStatus.QUARANTINE);

        // 只有采购、仓库能发起；数量不能超过在库
        call(login("viewer1"), "/api/supplier-returns", Map.of("batchId", b.getId(), "warehouseId", wh(), "qty", 1, "reason", "x")).andExpect(status().isForbidden());
        call(buyer, "/api/supplier-returns", Map.of("batchId", b.getId(), "warehouseId", wh(), "qty", 11, "reason", "x")).andExpect(status().isBadRequest());

        long id = createReturn(buyer, b, "6");
        // 发起时不扣库存，但占用额度：两张加起来不能超过在库
        assertThat(stockOf(b)).isEqualByComparingTo("10");
        call(buyer, "/api/supplier-returns", Map.of("batchId", b.getId(), "warehouseId", wh(), "qty", 5, "reason", "x")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("还能退 4")));
        assertThat(body(read(buyer, "/api/supplier-returns/candidates?supplierId=" + s)).get(0).get("available").decimalValue()).isEqualByComparingTo("4");
        assertThat(body(read(login("qa1"), "/api/dashboard")).toString()).contains("退供待签批");

        // 采购不能签批；待签批的供应商看不到
        call(buyer, "/api/supplier-returns/" + id + "/approve", Map.of("password", PWD)).andExpect(status().isForbidden());
        String sup = login("rs_a");
        assertThat(body(read(sup, "/api/portal/supplier/returns")).size()).isEqualTo(0);
        String qa = login("qa1");
        call(qa, "/api/supplier-returns/" + id + "/approve", Map.of("password", "wrong")).andExpect(status().isBadRequest());
        assertThat(stockOf(b)).isEqualByComparingTo("10");
        call(qa, "/api/supplier-returns/" + id + "/approve", Map.of("password", PWD)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DONE"));
        assertThat(stockOf(b)).isEqualByComparingTo("4");
        // 不能再签、不能再取消
        call(qa, "/api/supplier-returns/" + id + "/approve", Map.of("password", PWD)).andExpect(status().isBadRequest());
        call(buyer, "/api/supplier-returns/" + id + "/cancel", Map.of("reason", "x")).andExpect(status().isBadRequest());

        // 追溯能看到货去哪了，供应商看得到自己的，别家看不到
        JsonNode trace = body(read(qa, "/api/trace/" + b.getBatchNo()).andExpect(status().isOk()));
        assertThat(trace.get("supplierReturns").get(0).get("qty").decimalValue()).isEqualByComparingTo("6");
        assertThat(body(read(sup, "/api/portal/supplier/returns")).size()).isEqualTo(1);
        assertThat(body(read(login("rs_b"), "/api/portal/supplier/returns")).size()).isEqualTo(0);
        read(sup, "/api/supplier-returns").andExpect(status().isForbidden());

        // 取消后额度放出来
        long id2 = createReturn(buyer, b, "4");
        call(buyer, "/api/supplier-returns", Map.of("batchId", b.getId(), "warehouseId", wh(), "qty", 1, "reason", "x")).andExpect(status().isBadRequest());
        call(buyer, "/api/supplier-returns/" + id2 + "/cancel", Map.of("reason", "不退了")).andExpect(status().isOk());
        createReturn(buyer, b, "1");
        read(login("viewer1"), "/api/reports/supplier-returns").andExpect(status().isOk());
    }

    @Test
    void cannotReturnBatchesThatDidNotComeFromAPurchase() throws Exception {
        Batch fg = batches.findAll().stream().filter(x -> x.getSourceType() != com.cosmetic.scm.domain.SourceType.PO).findFirst().orElseThrow();
        call(login("purchase1"), "/api/supplier-returns", Map.of("batchId", fg.getId(), "warehouseId", wh(), "qty", 1, "reason", "x")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("不是采购来的")));
    }

    @Test
    void signerCannotBeTheMaker() throws Exception {
        Long s = supplier("SUP-RS-S", "rs_s");
        if (!users.existsByUsername("wh_qa")) {
            userService.newUser("wh_qa", "仓库兼质检", PWD, EnumSet.of(Role.WAREHOUSE, Role.QA), null, false);
        }
        Batch b = receive(login("purchase1"), s, "3", "1", "0", false);
        String both = login("wh_qa");
        long id = createReturn(both, b, "1");
        call(both, "/api/supplier-returns/" + id + "/approve", Map.of("password", PWD)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("不能是发起人")));
    }

    /**
     * 手算：批次 A 10 个 × 11.3，税 13%。
     * 结算前退 3：结算 7 × 11.3 = 79.10，税 79.10 × 13 / 113 = 9.10。
     * 结算后退 2：冲减 2 × 11.3 = -22.60，税 -2.60。新批次 B 5 × 10，税 0 = 50。下一张合计 27.40，税 -2.60。
     */
    @Test
    void returnsBeforeBillingReduceQtyAndAfterBillingBecomeCredits() throws Exception {
        Long s = supplier("SUP-RS-M", "rs_m");
        String buyer = login("purchase1");
        Batch a = receive(buyer, s, "10", "11.3", "13", true);
        returnDone(buyer, a, "3");

        JsonNode first = body(call(buyer, "/api/statements", Map.of("supplierId", s, "periodTo", today().toString())).andExpect(status().isOk()));
        assertThat(first.get("lines").size()).isEqualTo(1);
        assertThat(first.get("lines").get(0).get("qty").decimalValue()).isEqualByComparingTo("7");
        assertThat(first.get("totalAmount").decimalValue()).isEqualByComparingTo("79.10");
        assertThat(first.get("taxAmount").decimalValue()).isEqualByComparingTo("9.10");
        // 那 3 个不能再冲减一次
        assertThat(body(read(buyer, "/api/statements/preview?supplierId=" + s + "&periodTo=" + today())).get("lines").size()).isEqualTo(0);

        returnDone(buyer, a, "2");
        receive(buyer, s, "5", "10", "0", true);
        JsonNode pv = body(read(buyer, "/api/statements/preview?supplierId=" + s + "&periodTo=" + today()));
        assertThat(pv.get("lines").size()).isEqualTo(2);
        assertThat(pv.get("goodsAmount").decimalValue()).isEqualByComparingTo("27.40");
        JsonNode second = body(call(buyer, "/api/statements", Map.of("supplierId", s, "periodTo", today().toString())).andExpect(status().isOk()));
        assertThat(second.get("totalAmount").decimalValue()).isEqualByComparingTo("27.40");
        assertThat(second.get("taxAmount").decimalValue()).isEqualByComparingTo("-2.60");
        JsonNode credit = null;
        for (JsonNode l : second.get("lines")) {
            if (!l.get("returnNo").isNull()) {
                credit = l;
            }
        }
        assertThat(credit.get("qty").decimalValue()).isEqualByComparingTo("-2");
        assertThat(credit.get("amount").decimalValue()).isEqualByComparingTo("-22.60");
        assertThat(credit.get("unitPrice").decimalValue()).isEqualByComparingTo("11.3");
        // 同一笔退货不能再冲减
        assertThat(body(read(buyer, "/api/statements/preview?supplierId=" + s + "&periodTo=" + today())).get("lines").size()).isEqualTo(0);

        // 作废第二张：冲减和新批次都放回去，重新生成金额一样
        call(buyer, "/api/statements/" + second.get("id").asLong() + "/cancel", Map.of("reason", "重算")).andExpect(status().isOk());
        JsonNode third = body(call(buyer, "/api/statements", Map.of("supplierId", s, "periodTo", today().toString())).andExpect(status().isOk()));
        assertThat(third.get("totalAmount").decimalValue()).isEqualByComparingTo("27.40");

        // 作废第一张（还没审批）：批次 A 的已退 5 个都要减，结算 5 × 11.3 = 56.50，没有多算也没有少算
        call(buyer, "/api/statements/" + third.get("id").asLong() + "/cancel", Map.of("reason", "重算")).andExpect(status().isOk());
        call(buyer, "/api/statements/" + first.get("id").asLong() + "/cancel", Map.of("reason", "重算")).andExpect(status().isOk());
        JsonNode again = body(read(buyer, "/api/statements/preview?supplierId=" + s + "&periodTo=" + today()));
        // A 5 × 11.3 = 56.50，B 5 × 10 = 50
        assertThat(again.get("lines").size()).isEqualTo(2);
        assertThat(again.get("goodsAmount").decimalValue()).isEqualByComparingTo("106.50");
    }

    @Test
    void creditsLargerThanNewGoodsWaitAndFullReturnsAreNotBilled() throws Exception {
        Long s = supplier("SUP-RS-C", "rs_c");
        String buyer = login("purchase1");
        Batch a = receive(buyer, s, "10", "10", "0", true);
        call(buyer, "/api/statements", Map.of("supplierId", s, "periodTo", today().toString())).andExpect(status().isOk());
        // 结算后退货，没有新货款：未允许红字冲减时不能生成，提示可选的退款流程
        returnDone(buyer, a, "4");
        call(buyer, "/api/statements", Map.of("supplierId", s, "periodTo", today().toString())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("允许生成红字冲减对账单")));
        // 冲减还在，有新货款时一起算：-40 + 3 × 20 = 20
        receive(buyer, s, "3", "20", "0", true);
        JsonNode st = body(call(buyer, "/api/statements", Map.of("supplierId", s, "periodTo", today().toString())).andExpect(status().isOk()));
        assertThat(st.get("totalAmount").decimalValue()).isEqualByComparingTo("20");

        // 全部退回的批次没有货款
        Long s2 = supplier("SUP-RS-F", "rs_f");
        Batch f = receive(buyer, s2, "2", "10", "0", true);
        returnDone(buyer, f, "2");
        assertThat(stockOf(f)).isEqualByComparingTo("0");
        assertThat(body(read(buyer, "/api/statements/preview?supplierId=" + s2 + "&periodTo=" + today())).get("lines").size()).isEqualTo(0);
    }

    /** 冲减行的批次退回去时可能已判不合格，不能因此挡住整张对账单。 */
    @Test
    void creditLineDoesNotBlockApprovalWhenItsBatchIsRejected() throws Exception {
        Long s = supplier("SUP-RS-G", "rs_g");
        String buyer = login("purchase1");
        Batch a = receive(buyer, s, "10", "10", "0", true);
        call(buyer, "/api/statements", Map.of("supplierId", s, "periodTo", today().toString())).andExpect(status().isOk());
        returnDone(buyer, a, "1");
        a.setStatus(BatchStatus.REJECTED);
        batches.save(a);
        receive(buyer, s, "2", "20", "0", true);
        long id = body(call(buyer, "/api/statements", Map.of("supplierId", s, "periodTo", today().toString())).andExpect(status().isOk())).get("id").asLong();
        String sup = login("rs_g");
        call(sup, "/api/portal/supplier/statements/" + id + "/confirm", Map.of()).andExpect(status().isOk());
        call(sup, "/api/portal/supplier/statements/" + id + "/invoice", Map.of("invoiceNo", "RS-G", "invoiceDate", today().toString(), "amount", 30)).andExpect(status().isOk());
        call(login("finance1"), "/api/statements/" + id + "/approve", Map.of("password", PWD)).andExpect(status().isOk());
    }
}
