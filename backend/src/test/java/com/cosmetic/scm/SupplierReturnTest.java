package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

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
import com.cosmetic.scm.settlement.SupplierStatementRepository;
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
    @Autowired SupplierStatementRepository statements;

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

    private String reportSheet(String token, String key) throws Exception {
        byte[] xlsx = read(token, "/api/reports/" + key).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if ("xl/worksheets/sheet1.xml".equals(entry.getName())) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new AssertionError("导出文件缺少工作表");
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
    void returnHasToBeShippedBeforeTheSupplierCanConfirmReceipt() throws Exception {
        Long supplierId = supplier("SUP-RS-SHIP", "rs_ship");
        supplier("SUP-RS-OTHER", "rs_other");
        String buyer = login("purchase1");
        String qa = login("qa1");
        String sup = login("rs_ship");
        Batch b = receive(buyer, supplierId, "10", "1", "0", false);
        long id = createReturn(buyer, b, "3");
        String internal = "/api/supplier-returns/" + id;
        String receipt = "/api/portal/supplier/returns/" + id + "/confirm";
        var shipping = Map.of("carrier", "快递", "trackingNo", "RS-SHIP-001");

        call(buyer, internal + "/ship", shipping).andExpect(status().isBadRequest());
        String no = body(call(qa, internal + "/approve", Map.of("password", PWD)).andExpect(status().isOk()))
                .get("returnNo").asText();
        String todo = "$.todos[?(@.ref == '%s')]".formatted(no);
        read(buyer, "/api/dashboard").andExpect(status().isOk())
                .andExpect(jsonPath(todo + ".kind").value(contains("SUPPLIER_RETURN_SHIP")))
                .andExpect(jsonPath(todo + ".route").value(contains("/supplier-returns?no=" + no)));
        read(buyer, "/api/supplier-returns?returnNo=" + no).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(id));
        call(sup, receipt, Map.of()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("登记退货物流")));
        call(qa, internal + "/ship", shipping).andExpect(status().isForbidden());
        call(buyer, internal + "/ship", shipping).andExpect(status().isOk())
                .andExpect(jsonPath("$.shippedAt").isNotEmpty())
                .andExpect(jsonPath("$.offlineReceiptPending").value(false));
        call(buyer, internal + "/offline-receipt", Map.of("receivedOn", today(), "receiptReference", "DUP-RS-PORTAL", "password", PWD))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("在门户确认收货")));
        read(buyer, "/api/dashboard").andExpect(status().isOk()).andExpect(jsonPath(todo).isEmpty());

        call(login("rs_other"), receipt, Map.of()).andExpect(status().isNotFound());
        read(sup, "/api/portal/supplier/returns").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].trackingNo").value("RS-SHIP-001"));
        call(sup, receipt, Map.of("note", "数量核对无误")).andExpect(status().isOk())
                .andExpect(jsonPath("$.supplierConfirmedBy").value("rs_ship"))
                .andExpect(jsonPath("$.receiptSource").value("PORTAL"))
                .andExpect(jsonPath("$.supplierNote").value("数量核对无误"));
        call(buyer, internal + "/ship", shipping).andExpect(status().isBadRequest());
        assertThat(stockOf(b)).isEqualByComparingTo("7");
        assertThat(reportSheet(buyer, "supplier-returns")).contains("运单号", "RS-SHIP-001", "签收登记时间", "供应商门户", "数量核对无误");
    }

    @Test
    void buyerCanRecordEvidenceOfOfflineReceiptOnlyAfterShipment() throws Exception {
        Long supplierId = supplier("SUP-RS-OFFLINE", "rs_offline");
        var account = users.findByUsername("rs_offline").orElseThrow();
        account.setEnabled(false);
        users.save(account);
        String buyer = login("purchase1");
        String qa = login("qa1");
        Batch b = receive(buyer, supplierId, "10", "1", "0", false);
        long id = createReturn(buyer, b, "3");
        String internal = "/api/supplier-returns/" + id;
        String path = internal + "/offline-receipt";
        Map<String, Object> receipt = Map.of("receivedOn", today(), "receiptReference", "RC-OFFLINE-77", "password", PWD, "note", "纸质回单与数量一致");
        String no = body(call(qa, internal + "/approve", Map.of("password", PWD)).andExpect(status().isOk())).get("returnNo").asText();
        call(buyer, path, receipt).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("先登记退货物流")));
        var shipping = Map.of("carrier", "快递", "trackingNo", "WAY-OFFLINE-77");
        call(buyer, internal + "/ship", shipping).andExpect(status().isOk()).andExpect(jsonPath("$.offlineReceiptPending").value(true));
        String todo = "$.todos[?(@.ref == '%s')]".formatted(no);
        read(buyer, "/api/dashboard").andExpect(status().isOk())
                .andExpect(jsonPath(todo + ".kind").value(contains("SUPPLIER_RETURN_OFFLINE")))
                .andExpect(jsonPath(todo + ".route").value(contains("/supplier-returns?no=" + no)));
        call(qa, path, receipt).andExpect(status().isForbidden());
        call(buyer, path, Map.of("receivedOn", today(), "password", PWD, "receiptReference", " ")).andExpect(status().isBadRequest());
        call(buyer, path, Map.of("receivedOn", today(), "password", "wrong", "receiptReference", "RC-OFFLINE-77")).andExpect(status().isBadRequest());
        call(buyer, path, Map.of("receivedOn", today().minusDays(1), "password", PWD, "receiptReference", "RC-OFFLINE-77"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不能早于质量签批")));
        call(buyer, path, Map.of("receivedOn", today().plusDays(1), "password", PWD, "receiptReference", "RC-OFFLINE-77"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", containsString("不能晚于今天")));

        call(buyer, path, receipt).andExpect(status().isOk())
                .andExpect(jsonPath("$.supplierConfirmedBy").value("purchase1"))
                .andExpect(jsonPath("$.receiptSource").value("OFFLINE"))
                .andExpect(jsonPath("$.receivedOn").value(today().toString()))
                .andExpect(jsonPath("$.receiptReference").value("RC-OFFLINE-77"))
                .andExpect(jsonPath("$.offlineReceiptPending").value(false));
        call(buyer, path, receipt).andExpect(status().isBadRequest());
        call(buyer, internal + "/ship", shipping).andExpect(status().isBadRequest());
        assertThat(stockOf(b)).isEqualByComparingTo("7");
        read(buyer, "/api/dashboard").andExpect(status().isOk()).andExpect(jsonPath(todo).isEmpty());
        read(buyer, "/api/trace/" + b.getBatchNo()).andExpect(status().isOk())
                .andExpect(jsonPath("$.supplierReturns[0].receiptReference").value("RC-OFFLINE-77"));
        assertThat(reportSheet(buyer, "supplier-returns")).contains("采购代录", "RC-OFFLINE-77", "实际签收日期", "纸质回单与数量一致");

        var enabled = users.findByUsername("rs_offline").orElseThrow();
        enabled.setEnabled(true);
        users.save(enabled);
        String sup = login("rs_offline");
        read(sup, "/api/portal/supplier/returns").andExpect(status().isOk()).andExpect(jsonPath("$[0].receiptSource").value("OFFLINE"));
        call(sup, "/api/portal/supplier/returns/" + id + "/confirm", Map.of()).andExpect(status().isBadRequest());
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

    @Test
    void overdueRefundStaysOnTheFinanceQueueUntilTheFullRefundIsRecorded() throws Exception {
        Long supplierId = supplier("SUP-RS-REFUND", "rs_refund");
        String buyer = login("purchase1");
        String finance = login("finance1");
        String sup = login("rs_refund");
        Batch b = receive(buyer, supplierId, "10", "10", "0", true);
        var create = Map.of("supplierId", supplierId, "periodTo", today());
        long paidId = body(call(buyer, "/api/statements", create).andExpect(status().isOk())).get("id").asLong();
        String original = "/api/statements/" + paidId;
        call(sup, "/api/portal/supplier/statements/" + paidId + "/confirm", Map.of()).andExpect(status().isOk());
        call(buyer, original + "/invoice", Map.of("invoiceNo", "RS-ORIGINAL", "invoiceDate", today(), "amount", 100)).andExpect(status().isOk());
        call(finance, original + "/approve", Map.of("password", PWD)).andExpect(status().isOk());
        call(finance, original + "/pay", Map.of("payDate", today(), "amount", 100)).andExpect(status().isOk());

        returnDone(buyer, b, "4");
        JsonNode red = body(call(buyer, "/api/statements", Map.of("supplierId", supplierId, "periodTo", today(), "allowNegative", true))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalAmount").value(-40.0)));
        long id = red.get("id").asLong();
        String no = red.get("statementNo").asText();
        String internal = "/api/statements/" + id;
        String portal = "/api/portal/supplier/statements/" + id;
        // 旧单没有存到期日，使用对账截止日加账期。
        var legacy = statements.findById(id).orElseThrow();
        legacy.setDueDate(null);
        legacy.setPeriodTo(today().minusDays(35));
        statements.save(legacy);
        call(sup, portal + "/confirm", Map.of()).andExpect(status().isOk());
        call(sup, portal + "/invoices", Map.of("invoices", List.of(Map.of("invoiceNo", "RS-RED", "invoiceType", "RED",
                "invoiceDate", today(), "taxRate", 0, "amount", -40)))).andExpect(status().isOk());
        call(finance, internal + "/approve", Map.of("password", PWD)).andExpect(status().isOk())
                .andExpect(jsonPath("$.statusLabel").value("待供应商退款"));
        String listItem = "$[?(@.statementNo == '%s')]".formatted(no);
        String todo = "$.todos[?(@.ref == '%s')]".formatted(no);
        read(finance, "/api/dashboard").andExpect(status().isOk())
                .andExpect(jsonPath(todo + ".kind").value(contains("STATEMENT_REFUND")))
                .andExpect(jsonPath(todo + ".label").value(contains("供应商退款已逾期")))
                .andExpect(jsonPath(todo + ".level").value(contains("bad")))
                .andExpect(jsonPath(todo + ".route").value(contains("/settlement?no=" + no)));
        read(finance, "/api/statements?followUp=PAY").andExpect(status().isOk()).andExpect(jsonPath(listItem).isEmpty());
        read(finance, "/api/statements?followUp=REFUND").andExpect(status().isOk()).andExpect(jsonPath(listItem).isNotEmpty());
        read(finance, "/api/statements?followUp=OVERDUE").andExpect(status().isOk()).andExpect(jsonPath(listItem + ".overdueDays").value(contains(5)));
        read(login("viewer1"), "/api/statements?statementNo=" + no).andExpect(status().isForbidden());
        call(finance, internal + "/pay", Map.of("payDate", today(), "amount", 10)).andExpect(status().isBadRequest());
        call(finance, internal + "/refund", Map.of("payDate", today(), "amount", 15)).andExpect(status().isOk())
                .andExpect(jsonPath("$.unpaid").value(-25.0)).andExpect(jsonPath("$.payments[0].paymentType").value("REFUND"));
        read(finance, "/api/statements?statementNo=" + no).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].statusLabel").value("待供应商退款"));
        assertThat(reportSheet(finance, "statements")).contains("到期日", "逾期天数", "待供应商退款", "-25.00");
        call(finance, internal + "/refund", Map.of("payDate", today(), "amount", 26)).andExpect(status().isBadRequest());
        call(finance, internal + "/refund", Map.of("payDate", today(), "amount", 25)).andExpect(status().isOk())
                .andExpect(jsonPath("$.statusLabel").value("供应商已退款")).andExpect(jsonPath("$.overdue").value(false));
        read(finance, "/api/statements?followUp=OVERDUE").andExpect(status().isOk()).andExpect(jsonPath(listItem).isEmpty());
        read(finance, "/api/dashboard").andExpect(status().isOk()).andExpect(jsonPath(todo).isEmpty());
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
