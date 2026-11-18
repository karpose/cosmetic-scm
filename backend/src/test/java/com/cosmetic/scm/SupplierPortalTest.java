package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.PoStatus;
import com.cosmetic.scm.domain.Role;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.master.ItemRepository;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.PartnerRepository;
import com.cosmetic.scm.master.WarehouseRepository;
import com.cosmetic.scm.purchase.PurchaseOrder;
import com.cosmetic.scm.purchase.PurchaseOrderRepository;
import com.cosmetic.scm.qms.SupplierAuditService;
import com.cosmetic.scm.security.UserRepository;
import com.cosmetic.scm.security.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 供应商门户：只看本单位的单子和文件；交期留痕；送货通知不超量；准时率按第一次承诺算（手算核对）。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SupplierPortalTest {

    private static final byte[] PDF = "%PDF-1.4 test".getBytes(StandardCharsets.ISO_8859_1);

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    PartnerRepository partners;
    @Autowired
    ItemRepository items;
    @Autowired
    WarehouseRepository warehouses;
    @Autowired
    PurchaseOrderRepository orders;
    @Autowired
    BatchRepository batches;
    @Autowired
    UserRepository users;
    @Autowired
    UserService userService;
    @Autowired
    SupplierAuditService supplierAudits;
    @Autowired
    TimeProvider time;

    private LocalDate today() {
        return time.today();
    }

    private String login(String username) throws Exception {
        String resp = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", DemoDataLoader.DEMO_PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("token").asText();
    }

    private ResultActions call(String token, String url, Object body) throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private ResultActions read(String token, String url) throws Exception {
        return mvc.perform(get(url).header("Authorization", "Bearer " + token));
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private Long supplierId(String username) {
        return users.findByUsername(username).orElseThrow().getPartnerId();
    }

    /** 给 sup1 所属单位下一张单。qty 决定了送货通知的上限。 */
    private JsonNode newPo(Long supplierId, String qty, LocalDate expected) throws Exception {
        Long item = items.findAll().stream().filter(i -> "RM-GLY-002".equals(i.getCode())).findFirst().orElseThrow().getId();
        var body = new java.util.HashMap<String, Object>();
        body.put("supplierId", supplierId);
        body.put("expectedDate", expected);
        body.put("lines", List.of(Map.of("itemId", item, "qty", new BigDecimal(qty))));
        return body(call(login("purchase1"), "/api/purchase-orders", body).andExpect(status().isOk()));
    }

    private Map<String, Object> shipLine(JsonNode po, String qty, String batch) {
        return Map.of("poLineId", po.get("lines").get(0).get("id").asLong(), "qty", new BigDecimal(qty), "extBatchNo", batch,
                "mfgDate", today().minusDays(5).toString());
    }

    private Map<String, Object> shipment(Map<String, Object> line) {
        return Map.of("shipDate", today().toString(), "lines", List.of(line));
    }

    private void ensureOtherSupplierAccount() {
        if (!users.existsByUsername("sup2")) {
            Partner mp = partners.findByCode("SUP-MP").orElseGet(() -> partners.findAll().stream()
                    .filter(p -> p.getKind() == PartnerKind.SUPPLIER && !p.getId().equals(supplierId("sup1"))).findFirst().orElseThrow());
            userService.newUser("sup2", "第二家供应商", DemoDataLoader.DEMO_PASSWORD, EnumSet.of(Role.SUPPLIER_PORTAL), mp.getId(), false);
        }
    }

    @Test
    void supplierOnlyReachesOwnPortalAndOwnOrders() throws Exception {
        ensureOtherSupplierAccount();
        String sup1 = login("sup1");
        String sup2 = login("sup2");
        read(sup1, "/api/purchase-orders").andExpect(status().isForbidden());
        read(sup1, "/api/dashboard").andExpect(status().isForbidden());
        read(sup1, "/api/portal/oem/orders").andExpect(status().isForbidden());
        read(login("oem1"), "/api/portal/supplier/orders").andExpect(status().isForbidden());
        read(login("purchase1"), "/api/portal/supplier/orders").andExpect(status().isForbidden());

        JsonNode po = newPo(supplierId("sup1"), "5", today().plusDays(9));
        long id = po.get("id").asLong();
        assertThat(body(read(sup2, "/api/portal/supplier/orders").andExpect(status().isOk())).findValues("poNo").stream().map(JsonNode::asText))
                .doesNotContain(po.get("poNo").asText());
        // 别人的单子一律当“不存在”，不泄露单号是否存在
        call(sup2, "/api/portal/supplier/orders/" + id + "/promise", Map.of("promisedDate", today().plusDays(9).toString())).andExpect(status().isNotFound());
        call(sup2, "/api/portal/supplier/orders/" + id + "/shipments", shipment(shipLine(po, "1", "B"))).andExpect(status().isNotFound());
        read(sup2, "/api/portal/supplier/orders/" + id + "/attachments").andExpect(status().isNotFound());
        // 供应商账号不能绑到别的类型的单位上
        Partner customer = partners.findAll().stream().filter(p -> p.getKind() == PartnerKind.CUSTOMER).findFirst().orElseThrow();
        String admin = body(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", "admin", "password", "Admin@123456"))))).get("token").asText();
        call(admin, "/api/users", Map.of("username", "sup-bad", "displayName", "错绑", "password", "Strong@2026",
                "roles", List.of("SUPPLIER_PORTAL"), "partnerId", customer.getId()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("只能绑定")));
        // 供应商账号的邮箱要保存下来，每日提醒发到这里
        call(admin, "/api/users", Map.of("username", "sup-mail", "displayName", "有邮箱", "password", "Strong@2026",
                "roles", List.of("SUPPLIER_PORTAL"), "partnerId", supplierId("sup1"), "email", "sales@supplier.invalid")).andExpect(status().isOk());
        assertThat(users.findByUsername("sup-mail").orElseThrow().getEmail()).isEqualTo("sales@supplier.invalid");
        // 这个类不回滚，留着它别的测试会收到提醒邮件
        users.delete(users.findByUsername("sup-mail").orElseThrow());
        // 评分是内部的
        assertThat(body(read(sup1, "/api/portal/supplier/profile").andExpect(status().isOk())).has("score")).isFalse();
    }

    @Test
    void firstPromiseConfirmsLaterChangesNeedAReasonAndKeepTheFirstDate() throws Exception {
        String sup = login("sup1");
        JsonNode po = newPo(supplierId("sup1"), "5", today().plusDays(5));
        long id = po.get("id").asLong();
        String url = "/api/portal/supplier/orders/" + id + "/promise";

        call(sup, url, Map.of("promisedDate", today().minusDays(1).toString())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("交期不能早于今天"));
        call(sup, url, Map.of("promisedDate", today().plusDays(7).toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.promisedDate").value(today().plusDays(7).toString()));
        call(sup, url, Map.of("promisedDate", today().plusDays(12).toString())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("修改交期要写明原因"));
        call(sup, url, Map.of("promisedDate", today().plusDays(7).toString(), "note", "x")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("和当前交期相同，不需要修改"));
        call(sup, url, Map.of("promisedDate", today().plusDays(12).toString(), "note", "原料到货晚")).andExpect(status().isOk())
                .andExpect(jsonPath("$.promises.length()").value(2))
                .andExpect(jsonPath("$.promises[1].note").value("原料到货晚"));

        PurchaseOrder saved = orders.findById(id).orElseThrow();
        assertThat(saved.getFirstPromisedDate()).isEqualTo(today().plusDays(7));
        assertThat(saved.getPromisedDate()).isEqualTo(today().plusDays(12));
        // 内部能看到
        read(login("purchase1"), "/api/purchase-orders").andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")].promisedDate").value(today().plusDays(12).toString()));
    }

    @Test
    void shipmentNoticesCannotExceedWhatIsLeftAndReceivingSettlesThem() throws Exception {
        String sup = login("sup1");
        JsonNode po = newPo(supplierId("sup1"), "10", today().plusDays(5));
        long id = po.get("id").asLong();
        String url = "/api/portal/supplier/orders/" + id + "/shipments";

        call(sup, url, shipment(shipLine(po, "4", "B1"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请先确认订单并回复交期，再发送货通知"));
        call(sup, "/api/portal/supplier/orders/" + id + "/promise", Map.of("promisedDate", today().plusDays(5).toString())).andExpect(status().isOk());

        // 校验：超量、同行重复、过期、未来生产日期
        call(sup, url, shipment(shipLine(po, "11", "B1"))).andExpect(status().isBadRequest());
        call(sup, url, Map.of("shipDate", today().toString(), "lines", List.of(shipLine(po, "1", "A"), shipLine(po, "1", "B")))).andExpect(status().isBadRequest());
        var expired = new java.util.HashMap<>(shipLine(po, "1", "B"));
        expired.put("mfgDate", today().minusYears(2).toString());
        expired.put("expDate", today().minusDays(1).toString());
        call(sup, url, shipment(expired)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("已过有效期")));
        var future = new java.util.HashMap<>(shipLine(po, "1", "B"));
        future.put("mfgDate", today().plusDays(1).toString());
        call(sup, url, shipment(future)).andExpect(status().isBadRequest());

        // 通知 6，还能通知 4
        JsonNode first = body(call(sup, url, shipment(shipLine(po, "6", "B1"))).andExpect(status().isOk()));
        assertThat(first.get("lines").get(0).get("notifiedQty").decimalValue()).isEqualByComparingTo("6");
        call(sup, url, shipment(shipLine(po, "5", "B2"))).andExpect(status().isBadRequest());
        JsonNode second = body(call(sup, url, shipment(shipLine(po, "4", "B2"))).andExpect(status().isOk()));
        long firstShipment = second.get("shipments").get(1).get("id").asLong();
        long secondShipment = second.get("shipments").get(0).get("id").asLong();

        // 取消后额度放出来；别人取消不了
        ensureOtherSupplierAccount();
        call(login("sup2"), "/api/portal/supplier/shipments/" + secondShipment + "/cancel", Map.of()).andExpect(status().isNotFound());
        call(sup, "/api/portal/supplier/shipments/" + secondShipment + "/cancel", Map.of()).andExpect(status().isOk());
        call(sup, "/api/portal/supplier/shipments/" + secondShipment + "/cancel", Map.of()).andExpect(status().isBadRequest());
        JsonNode third = body(call(sup, url, shipment(shipLine(po, "4", "B3"))).andExpect(status().isOk()));
        long thirdShipment = third.get("shipments").get(0).get("id").asLong();

        // 仓库按第一张通知收货：通知变已收货，它的量释放，已收 6
        long wh = warehouses.findAll().stream().filter(w -> "WH-MAIN".equals(w.getCode())).findFirst().orElseThrow().getId();
        var line = Map.of("poLineId", po.get("lines").get(0).get("id").asLong(), "qty", 6, "extBatchNo", "B1", "warehouseId", wh);
        String wms = login("purchase1");
        call(wms, "/api/purchase-orders/" + id + "/receipts", Map.of("lines", List.of(line), "shipmentId", thirdShipment + 100000)).andExpect(status().isBadRequest());
        call(wms, "/api/purchase-orders/" + id + "/receipts", Map.of("lines", List.of(line), "shipmentId", firstShipment)).andExpect(status().isOk());
        // 同一张通知不能收两次
        var again = Map.of("poLineId", po.get("lines").get(0).get("id").asLong(), "qty", 1, "extBatchNo", "B1", "warehouseId", wh);
        call(wms, "/api/purchase-orders/" + id + "/receipts", Map.of("lines", List.of(again), "shipmentId", firstShipment)).andExpect(status().isBadRequest());
        PurchaseOrder saved = orders.findById(id).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PoStatus.PARTIAL);
        assertThat(saved.getLastReceivedOn()).isEqualTo(today());

        JsonNode view = body(read(sup, "/api/portal/supplier/orders").andExpect(status().isOk()));
        JsonNode mine = null;
        for (JsonNode o : view) {
            if (o.get("id").asLong() == id) {
                mine = o;
            }
        }
        assertThat(mine.get("lines").get(0).get("remaining").decimalValue()).isEqualByComparingTo("4");
        assertThat(mine.get("lines").get(0).get("notifiedQty").decimalValue()).isEqualByComparingTo("4");
        assertThat(mine.path("receiptDifferences").path(Long.toString(firstShipment)).isArray()).isTrue();
        assertThat(mine.path("receiptDifferences").path(Long.toString(firstShipment)).isEmpty()).isTrue();
        call(sup, "/api/portal/supplier/shipments/" + firstShipment + "/receipt-dispute", Map.of("note", "请核对"))
                .andExpect(status().isBadRequest());

        // 关闭采购单：没收的通知作废，总览不会一直显示待收货
        call(wms, "/api/purchase-orders/" + id + "/close", Map.of("reason", "余量不要了")).andExpect(status().isOk());
        mine = null;
        for (JsonNode o : body(read(sup, "/api/portal/supplier/orders"))) {
            if (o.get("id").asLong() == id) {
                mine = o;
            }
        }
        assertThat(mine.get("lines").get(0).get("notifiedQty").decimalValue()).isEqualByComparingTo("0");
        call(sup, url, shipment(shipLine(po, "1", "B4"))).andExpect(status().isBadRequest());
    }

    @Test
    void receiptsShowActualLotsDifferencesAndCurrentQualityOnlyToTheSupplier() throws Exception {
        String supplier = login("sup1");
        String purchase = login("purchase1");
        var itemIds = items.findAll().stream().filter(i -> List.of("RM-GLY-002", "RM-NIA-001").contains(i.getCode()))
                .collect(java.util.stream.Collectors.toMap(i -> i.getCode(), i -> i.getId()));
        LocalDate expected = today().plusDays(5);
        JsonNode po = body(call(purchase, "/api/purchase-orders", Map.of("supplierId", supplierId("sup1"), "expectedDate", expected,
                "lines", List.of(Map.of("itemId", itemIds.get("RM-GLY-002"), "qty", 10), Map.of("itemId", itemIds.get("RM-NIA-001"), "qty", 3))))
                .andExpect(status().isOk()));
        long id = po.path("id").asLong();
        String portal = "/api/portal/supplier/orders/" + id;
        String receive = "/api/purchase-orders/" + id + "/receipts";
        call(supplier, portal + "/promise", Map.of("promisedDate", expected)).andExpect(status().isOk());
        JsonNode notice = body(call(supplier, portal + "/shipments", shipment(shipLine(po, "6", "DECLARED-LOT")))
                .andExpect(status().isOk())).path("shipments").get(0);
        long shipmentId = notice.path("id").asLong();
        long wh = warehouses.findAll().stream().filter(w -> "WH-MAIN".equals(w.getCode())).findFirst().orElseThrow().getId();
        long notifiedLine = po.path("lines").get(0).path("id").asLong();
        long otherLine = po.path("lines").get(1).path("id").asLong();
        var actual = Map.of("poLineId", notifiedLine, "qty", 4, "extBatchNo", "ACTUAL-LOT", "warehouseId", wh);
        var other = Map.of("poLineId", otherLine, "qty", 3, "extBatchNo", "MANUAL-OTHER", "warehouseId", wh);
        call(purchase, receive, Map.of("shipmentId", shipmentId, "lines", List.of(actual, other))).andExpect(status().isBadRequest());
        assertThat(supplierOrder(supplier, id).path("receipts").isEmpty()).isTrue();

        String batchNo = body(call(purchase, receive, Map.of("shipmentId", shipmentId, "lines", List.of(actual)))
                .andExpect(status().isOk())).path("batchNos").get(0).asText();
        JsonNode afterReceipt = supplierOrder(supplier, id);
        JsonNode receipt = afterReceipt.path("receipts").get(0);
        assertThat(receipt.path("receivedOn").asText()).isEqualTo(today().toString());
        assertThat(receipt.path("itemCode").asText()).isEqualTo("RM-GLY-002");
        assertThat(receipt.path("extBatchNo").asText()).isEqualTo("ACTUAL-LOT");
        assertThat(receipt.path("shipmentNo").asText()).isEqualTo(notice.path("shipmentNo").asText());
        assertThat(receipt.path("qty").decimalValue()).isEqualByComparingTo("4");
        assertThat(receipt.path("qualityStatus").asText()).isEqualTo("QUARANTINE");
        JsonNode diff = afterReceipt.path("receiptDifferences").path(Long.toString(shipmentId)).get(0);
        assertThat(diff.path("notifiedQty").decimalValue()).isEqualByComparingTo("6");
        assertThat(diff.path("receivedQty").decimalValue()).isEqualByComparingTo("4");
        assertThat(diff.path("batchChanged").asBoolean()).isTrue();

        long batchId = batches.findByBatchNo(batchNo).orElseThrow().getId();
        call(login("qa1"), "/api/quality/batches/" + batchId + "/material-decision",
                Map.of("decision", "REJECTED", "reportNo", "INTERNAL-REPORT", "note", "内部检验说明", "password", DemoDataLoader.DEMO_PASSWORD))
                .andExpect(status().isOk());
        JsonNode checked = supplierOrder(supplier, id).path("receipts").get(0);
        assertThat(checked.path("qualityStatus").asText()).isEqualTo("REJECTED");
        assertThat(checked.path("qualityLabel").asText()).isEqualTo("不合格");
        assertThat(checked.toString()).doesNotContain(batchNo, "INTERNAL-REPORT", "内部检验说明", "warehouse");

        JsonNode unusedNotice = body(call(supplier, portal + "/shipments", shipment(shipLine(po, "4", "UNUSED-LOT")))
                .andExpect(status().isOk())).path("shipments").get(0);
        var manual = Map.of("poLineId", notifiedLine, "qty", 6, "extBatchNo", "MANUAL-LOT", "warehouseId", wh);
        call(purchase, receive, Map.of("lines", List.of(manual, other))).andExpect(status().isOk());
        JsonNode done = supplierOrder(supplier, id);
        assertThat(done.path("status").asText()).isEqualTo("RECEIVED");
        assertThat(done.path("shipments").get(0).path("status").asText()).isEqualTo("CLOSED");
        assertThat(done.path("shipments").get(1).path("status").asText()).isEqualTo("RECEIVED");
        assertThat(done.path("receiptDifferences").has(unusedNotice.path("id").asText())).isFalse();
        assertThat(done.path("receipts").size()).isEqualTo(3);
        for (JsonNode row : done.path("receipts")) {
            if (row.path("extBatchNo").asText().startsWith("MANUAL-")) {
                assertThat(row.path("shipmentNo").isNull()).isTrue();
            }
        }

        ensureOtherSupplierAccount();
        JsonNode otherOrders = body(read(login("sup2"), "/api/portal/supplier/orders").andExpect(status().isOk()));
        assertThat(otherOrders.toString()).doesNotContain(po.path("poNo").asText(), "ACTUAL-LOT", "MANUAL-LOT", "DECLARED-LOT");
    }

    private JsonNode supplierOrder(String token, long poId) throws Exception {
        for (JsonNode order : body(read(token, "/api/portal/supplier/orders").andExpect(status().isOk()))) {
            if (order.path("id").asLong() == poId) {
                return order;
            }
        }
        throw new AssertionError("找不到供应商采购单 " + poId);
    }

    @Test
    void supplierReceiptDisputeIsAnsweredOnceByPurchaseAndLeavesReceiptIntact() throws Exception {
        ensureOtherSupplierAccount();
        String supplier = login("sup1");
        String otherSupplier = login("sup2");
        String purchase = login("purchase1");
        String qa = login("qa1");
        JsonNode po = newPo(supplierId("sup1"), "5", today().plusDays(5));
        long poId = po.path("id").asLong();
        String portalOrder = "/api/portal/supplier/orders/" + poId;
        call(supplier, portalOrder + "/promise", Map.of("promisedDate", today().plusDays(5))).andExpect(status().isOk());
        JsonNode notice = body(call(supplier, portalOrder + "/shipments", shipment(shipLine(po, "5", "NOTICE-LOT")))
                .andExpect(status().isOk())).path("shipments").get(0);
        long shipmentId = notice.path("id").asLong();
        String report = "/api/portal/supplier/shipments/" + shipmentId + "/receipt-dispute";
        String detail = "/api/purchase-orders/shipments/" + shipmentId + "/receipt-dispute";
        String resolve = detail + "/resolve";
        String todoRoute = "/purchase?receiptDispute=" + shipmentId;
        var reportBody = Map.of("note", "  请核对外箱与送货单的批号  ");
        var answer = Map.of("resolution", "  已核对外箱标签，按实收批号记录  ");
        call(supplier, report, reportBody).andExpect(status().isBadRequest());
        call(purchase, resolve, answer).andExpect(status().isNotFound());

        long wh = warehouses.findAll().stream().filter(w -> "WH-MAIN".equals(w.getCode())).findFirst().orElseThrow().getId();
        var line = Map.of("poLineId", po.path("lines").get(0).path("id").asLong(), "qty", 5, "extBatchNo", "RECEIPT-DISPUTE-LOT",
                "warehouseId", wh);
        call(purchase, "/api/purchase-orders/" + poId + "/receipts", Map.of("shipmentId", shipmentId, "lines", List.of(line)))
                .andExpect(status().isOk());
        JsonNode originalReceipt = supplierOrder(supplier, poId).path("receipts");
        call(otherSupplier, report, reportBody).andExpect(status().isNotFound());
        call(supplier, report, Map.of("note", " ")).andExpect(status().isBadRequest());

        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> attempt = () -> call(supplier, report, reportBody).andReturn().getResponse().getStatus();
            var submissions = pool.invokeAll(List.of(attempt, attempt), 20, TimeUnit.SECONDS);
            assertThat(List.of(submissions.get(0).get(), submissions.get(1).get())).containsExactlyInAnyOrder(200, 400);
        } finally {
            pool.shutdownNow();
        }
        JsonNode pending = supplierOrder(supplier, poId).path("shipments").get(0).path("receiptDispute");
        assertThat(pending.path("note").asText()).isEqualTo("请核对外箱与送货单的批号");
        assertThat(pending.path("reportedAt").asText()).isNotBlank();
        assertThat(pending.path("resolvedAt").isNull()).isTrue();
        read(qa, detail).andExpect(status().isForbidden());
        call(qa, resolve, answer).andExpect(status().isForbidden());
        call(supplier, resolve, answer).andExpect(status().isForbidden());
        JsonNode purchaseDetail = body(read(purchase, detail).andExpect(status().isOk()));
        assertThat(purchaseDetail.path("received").get(0).path("extBatchNo").asText()).isEqualTo("RECEIPT-DISPUTE-LOT");
        assertThat(purchaseDetail.path("differences").get(0).path("batchChanged").asBoolean()).isTrue();
        assertThat(body(read(purchase, "/api/dashboard").andExpect(status().isOk())).path("todos").findValuesAsText("route"))
                .contains(todoRoute);
        assertThat(body(read(qa, "/api/purchase-orders/" + poId).andExpect(status().isOk())).toString())
                .doesNotContain(pending.path("note").asText());

        call(purchase, resolve, Map.of("resolution", " ")).andExpect(status().isBadRequest());
        call(purchase, resolve, answer).andExpect(status().isOk());
        call(purchase, resolve, Map.of("resolution", "覆盖已发送答复")).andExpect(status().isBadRequest());
        call(supplier, report, reportBody).andExpect(status().isBadRequest());
        JsonNode finalized = supplierOrder(supplier, poId);
        JsonNode reply = finalized.path("shipments").get(0).path("receiptDispute");
        assertThat(reply.path("resolution").asText()).isEqualTo("已核对外箱标签，按实收批号记录");
        assertThat(reply.path("resolvedAt").asText()).isNotBlank();
        assertThat(finalized.path("receipts")).isEqualTo(originalReceipt);
        assertThat(body(read(purchase, "/api/dashboard").andExpect(status().isOk())).path("todos").findValuesAsText("route"))
                .doesNotContain(todoRoute);
        assertThat(body(read(otherSupplier, "/api/portal/supplier/orders").andExpect(status().isOk())).toString())
                .doesNotContain(pending.path("note").asText(), reply.path("resolution").asText());
    }

    @Test
    void openShipmentsAndLateOrdersShowOnTheDashboard() throws Exception {
        // 示例数据里有一张已发货待收的通知
        String body = read(login("purchase1"), "/api/dashboard").andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("供应商已发货").contains("ASN-");
        PurchaseOrder late = direct(supplierId("sup1"), PoStatus.OPEN, today().minusDays(2), null, null);
        body = read(login("purchase1"), "/api/dashboard").andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("采购单超交期").contains(late.getPoNo());
        orders.delete(late);
    }

    /** 准时率的标准答案：交期按第一次承诺算，手算 5 张到期、1 张准时。 */
    @Test
    void onTimeRateUsesTheFirstPromisedDate() {
        Partner p = new Partner();
        p.setCode("SUP-OTD");
        p.setName("准时率测试供应商");
        p.setKind(PartnerKind.SUPPLIER);
        partners.save(p);
        Long s = p.getId();
        LocalDate t = today();

        // 准时：第一次承诺 t-3，t-3 到齐
        direct(s, PoStatus.RECEIVED, t.minusDays(3), t.minusDays(3), null);
        // 迟到：第一次承诺 t-10，t-3 才到。后来把交期改到 t-2 也不算准时
        PurchaseOrder changed = direct(s, PoStatus.RECEIVED, t.minusDays(10), t.minusDays(3), null);
        changed.setPromisedDate(t.minusDays(2));
        orders.save(changed);
        // 已过交期没到齐：没收货、部分收货都算迟到
        direct(s, PoStatus.OPEN, t.minusDays(1), null, null);
        direct(s, PoStatus.PARTIAL, t.minusDays(1), t.minusDays(2), null);
        // 提前关闭（没到齐）算没按时，即使最后一次收货在交期内
        direct(s, PoStatus.CLOSED, t.minusDays(1), t.minusDays(5), null);
        // 不计：还没到交期；取消；没有任何交期；一年以前的
        direct(s, PoStatus.OPEN, t.plusDays(3), null, null);
        direct(s, PoStatus.CANCELLED, t.minusDays(1), null, null);
        direct(s, PoStatus.RECEIVED, null, t.minusDays(1), null);
        direct(s, PoStatus.RECEIVED, t.minusMonths(13), t.minusMonths(13), null);
        // 没承诺过的按采购期望：t-4 期望，t-4 到齐，准时。所以共 6 张到期 2 张准时
        direct(s, PoStatus.RECEIVED, null, t.minusDays(4), t.minusDays(4));

        SupplierAuditService.PartnerRow row = supplierAudits.rowOf(s);
        assertThat(row.dueTotal()).isEqualTo(6);
        assertThat(row.onTime()).isEqualTo(2);
        // 只有交货一项有数据：2/6 = 33 分，D
        assertThat(row.score()).isEqualTo(33);
        assertThat(row.grade()).isEqualTo("D");
    }

    private int seq;

    private PurchaseOrder direct(Long supplier, PoStatus status, LocalDate firstPromise, LocalDate lastReceived, LocalDate expected) {
        PurchaseOrder po = new PurchaseOrder();
        po.setPoNo("PO-TEST-" + System.nanoTime() + "-" + (seq++));
        po.setSupplierId(supplier);
        po.setOrderDate(today().minusMonths(14));
        po.setStatus(status);
        po.setCreatedBy("test");
        po.setCreatedAt(LocalDateTime.now());
        po.setFirstPromisedDate(firstPromise);
        po.setPromisedDate(firstPromise);
        po.setLastReceivedOn(lastReceived);
        po.setExpectedDate(expected);
        return orders.save(po);
    }

    @Test
    void supplierOnlySeesFilesItsOwnAccountsUploaded() throws Exception {
        ensureOtherSupplierAccount();
        String sup1 = login("sup1");
        String sup2 = login("sup2");
        String inside = login("purchase1");
        JsonNode po = newPo(supplierId("sup1"), "5", today().plusDays(5));
        long id = po.get("id").asLong();

        // 内部传到这张单上的文件，供应商看不到也下不了
        long internalId = body(mvc.perform(multipart("/api/attachments").file(new MockMultipartFile("file", "内部审核报告.pdf", "application/pdf", PDF))
                .param("ownerType", "PURCHASE_ORDER").param("ownerId", String.valueOf(id)).header("Authorization", "Bearer " + inside))
                .andExpect(status().isOk())).get("id").asLong();
        read(sup1, "/api/portal/supplier/orders/" + id + "/attachments").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        read(sup1, "/api/portal/supplier/attachments/" + internalId + "/download").andExpect(status().isNotFound());

        long coa = body(mvc.perform(multipart("/api/portal/supplier/orders/" + id + "/attachments")
                .file(new MockMultipartFile("file", "COA.pdf", "application/pdf", PDF)).header("Authorization", "Bearer " + sup1))
                .andExpect(status().isOk())).get("id").asLong();
        read(sup1, "/api/portal/supplier/orders/" + id + "/attachments").andExpect(jsonPath("$.length()").value(1));
        read(sup1, "/api/portal/supplier/attachments/" + coa + "/download").andExpect(status().isOk()).andExpect(content().bytes(PDF));
        // 别的供应商下不了；内部能看到
        read(sup2, "/api/portal/supplier/attachments/" + coa + "/download").andExpect(status().isNotFound());
        read(inside, "/api/attachments?ownerType=PURCHASE_ORDER&ownerId=" + id).andExpect(jsonPath("$.length()").value(2));

        // 资质文件：内部传到本单位的审核报告不给看，自己传的能看，文件头校验同样生效
        Long partner = supplierId("sup1");
        mvc.perform(multipart("/api/attachments").file(new MockMultipartFile("file", "审核报告.pdf", "application/pdf", PDF))
                .param("ownerType", "PARTNER").param("ownerId", String.valueOf(partner)).header("Authorization", "Bearer " + login("qa1"))).andExpect(status().isOk());
        int before = body(read(sup1, "/api/portal/supplier/profile/attachments")).size();
        mvc.perform(multipart("/api/portal/supplier/profile/attachments").file(new MockMultipartFile("file", "新资质.pdf", "application/pdf", PDF))
                .header("Authorization", "Bearer " + sup1)).andExpect(status().isOk());
        JsonNode files = body(read(sup1, "/api/portal/supplier/profile/attachments"));
        assertThat(files.size()).isEqualTo(before + 1);
        assertThat(files.findValues("fileName").stream().map(JsonNode::asText)).doesNotContain("审核报告.pdf");
        mvc.perform(multipart("/api/portal/supplier/profile/attachments").file(new MockMultipartFile("file", "假的.pdf", "application/pdf", "MZ".getBytes(StandardCharsets.ISO_8859_1)))
                .header("Authorization", "Bearer " + sup1)).andExpect(status().isBadRequest());
    }

    @Test
    void shipmentLineCoaAttachesToReceivedBatchAndPrefillsInspection() throws Exception {
        ensureOtherSupplierAccount();
        String sup1 = login("sup1");
        String sup2 = login("sup2");
        String purchase = login("purchase1");
        JsonNode po = newPo(supplierId("sup1"), "5", today().plusDays(5));
        long poId = po.path("id").asLong();
        long poLineId = po.path("lines").get(0).path("id").asLong();
        call(sup1, "/api/portal/supplier/orders/" + poId + "/promise", Map.of("promisedDate", today().plusDays(5))).andExpect(status().isOk());

        // 创建送货通知时填报行级 COA 报告编号，并按明细行上传 COA PDF
        JsonNode shipped = body(call(sup1, "/api/portal/supplier/orders/" + poId + "/shipments", Map.of(
                "shipDate", today().toString(),
                "lines", List.of(Map.of("poLineId", poLineId, "qty", 5, "extBatchNo", "COA-LOT-01", "mfgDate", today().minusDays(2).toString(), "coaNo", "COA-2026-888"))
        )).andExpect(status().isOk()));
        long shipmentId = shipped.path("shipments").get(0).path("id").asLong();
        long shipmentLineId = shipped.path("shipments").get(0).path("lines").get(0).path("id").asLong();

        // 别的供应商不能上传或查看
        mvc.perform(multipart("/api/portal/supplier/shipment-lines/" + shipmentLineId + "/coa")
                .file(new MockMultipartFile("file", "coa.pdf", "application/pdf", PDF)).header("Authorization", "Bearer " + sup2))
                .andExpect(status().isNotFound());
        long coaAttId = body(mvc.perform(multipart("/api/portal/supplier/shipment-lines/" + shipmentLineId + "/coa")
                .file(new MockMultipartFile("file", "COA-LOT-01.pdf", "application/pdf", PDF)).header("Authorization", "Bearer " + sup1))
                .andExpect(status().isOk())).path("id").asLong();
        read(sup1, "/api/portal/supplier/shipment-lines/" + shipmentLineId + "/attachments").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        read(sup1, "/api/portal/supplier/attachments/" + coaAttId + "/download").andExpect(status().isOk()).andExpect(content().bytes(PDF));

        // 仓库按该送货通知收货：自动将 COA 编号和附件挂载到生成的批次
        long wh = warehouses.findAll().stream().filter(w -> "WH-MAIN".equals(w.getCode())).findFirst().orElseThrow().getId();
        String batchNo = body(call(purchase, "/api/purchase-orders/" + poId + "/receipts", Map.of(
                "shipmentId", shipmentId,
                "lines", List.of(Map.of("poLineId", poLineId, "qty", 5, "extBatchNo", "COA-LOT-01", "warehouseId", wh))
        )).andExpect(status().isOk())).path("batchNos").get(0).asText();
        long batchId = batches.findByBatchNo(batchNo).orElseThrow().getId();

        // 批次附件与质量检验面板均已自动带入
        String qa = login("qa1");
        long batchAttachmentId = body(read(qa, "/api/attachments?ownerType=BATCH&ownerId=" + batchId).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].fileName").value("COA-LOT-01.pdf"))).get(0).path("id").asLong();
        assertThat(batchAttachmentId).isNotEqualTo(coaAttId);
        read(qa, "/api/attachments/" + batchAttachmentId + "/download").andExpect(status().isOk()).andExpect(content().bytes(PDF));
        read(sup1, "/api/portal/supplier/attachments/" + coaAttId + "/download").andExpect(status().isOk()).andExpect(content().bytes(PDF));
        read(qa, "/api/quality/batches/" + batchId + "/inspections").andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultCoaNo").value("COA-2026-888"));
    }
}
