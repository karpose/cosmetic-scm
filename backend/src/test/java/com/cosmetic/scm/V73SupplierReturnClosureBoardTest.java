package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.domain.SourceType;
import com.cosmetic.scm.domain.SupplierReturnStatus;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.master.WarehouseRepository;
import com.cosmetic.scm.supplierreturn.SupplierReturn;
import com.cosmetic.scm.supplierreturn.SupplierReturnRepository;
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

/** V73 退供单执行与供应商签收闭环监控看板及报表导出集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-v73;MODE=MySQL;DB_CLOSE_DELAY=-1")
class V73SupplierReturnClosureBoardTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TimeProvider time;
    @Autowired BatchRepository batches;
    @Autowired WarehouseRepository warehouses;
    @Autowired SupplierReturnRepository returnRepo;

    private String login(String u) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", u, "password", PWD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void closureBoardTracksOverdueSignShipAndReceiptAndExportsReport() throws Exception {
        LocalDateTime now = time.now();
        Batch poBatch = batches.findAll().stream()
                .filter(b -> b.getSourceType() == SourceType.PO && b.getPartnerId() != null)
                .findFirst().orElseThrow();
        Long whId = warehouses.findAll().get(0).getId();

        // 1) 已发运 10 天仍未签收 -> OVERDUE (agingDays = 10 > 7)
        SupplierReturn overdueReceipt = new SupplierReturn();
        overdueReceipt.setReturnNo("RS-V73-OVER");
        overdueReceipt.setSupplierId(poBatch.getPartnerId());
        overdueReceipt.setBatchId(poBatch.getId());
        overdueReceipt.setWarehouseId(whId);
        overdueReceipt.setQty(new BigDecimal("5"));
        overdueReceipt.setReason("V73 来料含量偏低退供");
        overdueReceipt.setStatus(SupplierReturnStatus.DONE);
        overdueReceipt.setCreatedBy("purchase1");
        overdueReceipt.setCreatedAt(now.minusDays(13));
        overdueReceipt.setSignedBy("qa1");
        overdueReceipt.setSignedAt(now.minusDays(12));
        overdueReceipt.setCarrier("顺丰速运");
        overdueReceipt.setTrackingNo("SF-V73-001");
        overdueReceipt.setShippedBy("purchase1");
        overdueReceipt.setShippedAt(now.minusDays(10));
        returnRepo.save(overdueReceipt);

        // 2) 已签批 1 天待登记物流 -> PENDING_SHIP (未超期)
        SupplierReturn pendingShip = new SupplierReturn();
        pendingShip.setReturnNo("RS-V73-SHIP");
        pendingShip.setSupplierId(poBatch.getPartnerId());
        pendingShip.setBatchId(poBatch.getId());
        pendingShip.setWarehouseId(whId);
        pendingShip.setQty(new BigDecimal("3"));
        pendingShip.setReason("V73 外包装破损退供");
        pendingShip.setStatus(SupplierReturnStatus.DONE);
        pendingShip.setCreatedBy("purchase1");
        pendingShip.setCreatedAt(now.minusDays(2));
        pendingShip.setSignedBy("qa1");
        pendingShip.setSignedAt(now.minusDays(1));
        returnRepo.save(pendingShip);

        // 3) 发起 5 天仍待质量签批 -> PENDING_SIGN 且 OVERDUE (agingDays = 5 > 3)
        SupplierReturn overdueSign = new SupplierReturn();
        overdueSign.setReturnNo("RS-V73-SIGN");
        overdueSign.setSupplierId(poBatch.getPartnerId());
        overdueSign.setBatchId(poBatch.getId());
        overdueSign.setWarehouseId(whId);
        overdueSign.setQty(new BigDecimal("2"));
        overdueSign.setReason("V73 微生物复检超标退供");
        overdueSign.setStatus(SupplierReturnStatus.PENDING);
        overdueSign.setCreatedBy("purchase1");
        overdueSign.setCreatedAt(now.minusDays(5));
        returnRepo.save(overdueSign);

        String token = login("purchase1");
        JsonNode board = json.readTree(mvc.perform(get("/api/supplier-returns/closure-board")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(board.get("overdueCount").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(board.get("pendingSignCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(board.get("pendingShipCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(board.get("awaitingReceiptCount").asInt()).isGreaterThanOrEqualTo(1);

        JsonNode overRow = findByReturnNo(board.get("rows"), "RS-V73-OVER");
        JsonNode shipRow = findByReturnNo(board.get("rows"), "RS-V73-SHIP");
        JsonNode signRow = findByReturnNo(board.get("rows"), "RS-V73-SIGN");
        assertThat(overRow).isNotNull();
        assertThat(overRow.get("overdue").asBoolean()).isTrue();
        assertThat(overRow.get("riskLevel").asText()).isEqualTo("OVERDUE");
        assertThat(overRow.get("agingDays").asLong()).isEqualTo(10L);

        assertThat(shipRow).isNotNull();
        assertThat(shipRow.get("overdue").asBoolean()).isFalse();
        assertThat(shipRow.get("stage").asText()).isEqualTo("PENDING_SHIP");
        assertThat(shipRow.get("agingDays").asLong()).isEqualTo(1L);

        assertThat(signRow).isNotNull();
        assertThat(signRow.get("overdue").asBoolean()).isTrue();
        assertThat(signRow.get("stage").asText()).isEqualTo("PENDING_SIGN");
        assertThat(signRow.get("agingDays").asLong()).isEqualTo(5L);

        byte[] xlsx = mvc.perform(get("/api/reports/supplier-return-closure").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(xlsx.length).isGreaterThan(100);
    }

    private static JsonNode findByReturnNo(JsonNode arr, String returnNo) {
        for (JsonNode n : arr) {
            if (returnNo.equals(n.get("returnNo").asText())) {
                return n;
            }
        }
        return null;
    }
}
