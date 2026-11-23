package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.domain.ChannelOrderStatus;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.WarehouseKind;
import com.cosmetic.scm.integration.ChannelOrder;
import com.cosmetic.scm.integration.ChannelOrderLine;
import com.cosmetic.scm.integration.ChannelOrderLineRepository;
import com.cosmetic.scm.integration.ChannelOrderRepository;
import com.cosmetic.scm.integration.IntegrationLog;
import com.cosmetic.scm.integration.IntegrationLogRepository;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.ItemRepository;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.PartnerRepository;
import com.cosmetic.scm.master.Warehouse;
import com.cosmetic.scm.master.WarehouseRepository;
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

/** V77 电商与接口集成异常监控与重试闭环看板及报表导出集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-v77;MODE=MySQL;DB_CLOSE_DELAY=-1")
class V77IntegrationExceptionBoardTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TimeProvider time;
    @Autowired ChannelOrderRepository orderRepo;
    @Autowired ChannelOrderLineRepository lineRepo;
    @Autowired IntegrationLogRepository logRepo;
    @Autowired PartnerRepository partnerRepo;
    @Autowired WarehouseRepository warehouseRepo;
    @Autowired ItemRepository itemRepo;

    private String login(String u) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", u, "password", PWD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void exceptionBoardClassifiesRisksAndCausesAndExportsReport() throws Exception {
        LocalDateTime now = time.now();
        Partner customer = partnerRepo.findAll().stream()
                .filter(p -> p.getKind() == PartnerKind.CUSTOMER).findFirst().orElseThrow();
        Warehouse wh = warehouseRepo.findByKind(WarehouseKind.OWN).get(0);
        Item fg = itemRepo.findByItemTypeOrderByCode(ItemType.FG).get(0);

        // 1) 超 24 小时严重滞留 + 库存不足 -> CRITICAL / STOCK_SHORTAGE
        ChannelOrder criticalOrder = saveOrder("TMALL", "V77-TM-CRITICAL", customer.getId(), wh.getId(),
                ChannelOrderStatus.FAILED, 3, now.minusHours(30), "成品可用库存不足，无法出库");
        saveLine(criticalOrder.getId(), fg.getCode(), fg.getId(), new BigDecimal("5"));

        // 2) SKU 未匹配 -> HIGH / SKU_UNMAPPED
        ChannelOrder skuOrder = saveOrder("JD", "V77-JD-SKU", customer.getId(), wh.getId(),
                ChannelOrderStatus.FAILED, 1, now.minusHours(2), "SKU 没有匹配到在售成品：FG-UNKNOWN-999（按物料编码匹配）");
        saveLine(skuOrder.getId(), "FG-UNKNOWN-999", null, new BigDecimal("2"));

        // 3) 近期库存/效期不足 -> MEDIUM / STOCK_SHORTAGE
        ChannelOrder mediumOrder = saveOrder("DOUYIN", "V77-DY-MEDIUM", customer.getId(), wh.getId(),
                ChannelOrderStatus.FAILED, 1, now.minusHours(1), "满足客户效期门槛的可售库存不足");
        saveLine(mediumOrder.getId(), fg.getCode(), fg.getId(), new BigDecimal("3"));

        String creditError = "客户 测试店铺 信用额度不足：授信 ¥987654.32，当前未结应收 ¥109876.54，本次出库 ¥882345.67";
        ChannelOrder creditOrder = saveOrder("TMALL", "V77-TM-CREDIT", customer.getId(), wh.getId(),
                ChannelOrderStatus.FAILED, 1, now.minusHours(1), creditError);
        saveLine(creditOrder.getId(), fg.getCode(), fg.getId(), BigDecimal.ONE);

        // 近 7 日对接日志（含 1 条电商失败与 1 条财务失败，验证非涉价角色不计入 FINANCE 失败数）
        saveLog("IN", "TMALL", "推送订单", "V77-TM-CRITICAL", false, "出库失败 1", now.minusHours(5));
        saveLog("IN", "TMALL", "推送订单", "V77-OK", true, "共 1 单，出库失败 0", now.minusHours(4));
        saveLog("OUT", "FINANCE", "凭证回执", "ST-V77", false, "财务接口超时", now.minusHours(3));

        String salesToken = login("sales1");
        JsonNode salesBoard = json.readTree(mvc.perform(get("/api/channel-orders/exception-board")
                        .header("Authorization", "Bearer " + salesToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(salesBoard.get("totalExceptions").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(salesBoard.get("criticalCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(salesBoard.get("highCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(salesBoard.get("skuUnmappedCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(salesBoard.get("stockShortageCount").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(salesBoard.get("overdue24hCount").asInt()).isGreaterThanOrEqualTo(1);

        JsonNode rows = salesBoard.get("rows");
        int idxCritical = indexOfOrder(rows, "V77-TM-CRITICAL");
        int idxSku = indexOfOrder(rows, "V77-JD-SKU");
        int idxMedium = indexOfOrder(rows, "V77-DY-MEDIUM");
        assertThat(idxCritical).isGreaterThanOrEqualTo(0);
        assertThat(idxSku).isGreaterThan(idxCritical);
        assertThat(idxMedium).isGreaterThan(idxSku);

        assertThat(rows.get(idxCritical).get("riskLevel").asText()).isEqualTo("CRITICAL");
        assertThat(rows.get(idxCritical).get("causeCode").asText()).isEqualTo("STOCK_SHORTAGE");
        assertThat(rows.get(idxCritical).get("agingHours").asLong()).isGreaterThanOrEqualTo(30L);

        assertThat(rows.get(idxSku).get("riskLevel").asText()).isEqualTo("HIGH");
        assertThat(rows.get(idxSku).get("causeCode").asText()).isEqualTo("SKU_UNMAPPED");
        assertThat(rows.get(idxSku).get("recommendedAction").asText()).contains("FG-UNKNOWN-999");

        assertThat(rows.get(idxMedium).get("riskLevel").asText()).isEqualTo("MEDIUM");
        assertThat(rows.get(idxMedium).get("causeCode").asText()).isEqualTo("STOCK_SHORTAGE");

        int idxCredit = indexOfOrder(rows, "V77-TM-CREDIT");
        assertThat(idxCredit).isGreaterThanOrEqualTo(0);
        assertThat(rows.get(idxCredit).get("causeCode").asText()).isEqualTo("CREDIT_LIMIT");
        assertThat(rows.get(idxCredit).get("error").asText()).isEqualTo("客户信用额度不足");

        // 财务可统计到 FINANCE 失败日志，也可查看具体授信金额。
        String financeToken = login("finance1");
        JsonNode financeBoard = json.readTree(mvc.perform(get("/api/channel-orders/exception-board")
                        .header("Authorization", "Bearer " + financeToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(financeBoard.get("recent7dFailedCalls").asLong())
                .isGreaterThan(salesBoard.get("recent7dFailedCalls").asLong());
        JsonNode financeRows = financeBoard.get("rows");
        assertThat(financeRows.get(indexOfOrder(financeRows, "V77-TM-CREDIT")).get("error").asText())
                .isEqualTo(creditError);

        byte[] xlsx = mvc.perform(get("/api/reports/integration-exceptions")
                        .header("Authorization", "Bearer " + salesToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if ("xl/worksheets/sheet1.xml".equals(entry.getName())) {
                    assertThat(new String(zip.readAllBytes(), StandardCharsets.UTF_8))
                            .contains("V77-TM-CREDIT", "客户信用额度不足")
                            .doesNotContain("987654.32", "109876.54", "882345.67");
                    return;
                }
            }
        }
        throw new AssertionError("导出文件缺少第一张工作表");
    }

    @Test
    void retryResolvesPreviouslyUnmappedSkuAndShips() throws Exception {
        Partner customer = partnerRepo.findByCode("CUS-SY").orElseThrow();
        Warehouse wh = warehouseRepo.findByKind(WarehouseKind.OWN).stream()
                .filter(w -> "WH-MAIN".equals(w.getCode())).findFirst().orElseThrow();
        Item fg = itemRepo.findByItemTypeOrderByCode(ItemType.FG).stream()
                .filter(i -> "FG-SER-001".equals(i.getCode())).findFirst().orElseThrow();
        ChannelOrder failed = saveOrder("TMALL", "V77-RETRY-SKU", customer.getId(), wh.getId(),
                ChannelOrderStatus.FAILED, 1, time.now(), "SKU 没有匹配到在售成品：" + fg.getCode());
        saveLine(failed.getId(), fg.getCode(), null, BigDecimal.ONE);

        String salesToken = login("sales1");
        mvc.perform(post("/api/channel-orders/{id}/retry", failed.getId()).header("Authorization", "Bearer " + salesToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SHIPPED"));
        assertThat(orderRepo.findById(failed.getId()).orElseThrow().getSoId()).isNotNull();
        assertThat(lineRepo.findByOrderIdIn(List.of(failed.getId()))).singleElement()
                .extracting(ChannelOrderLine::getItemId).isEqualTo(fg.getId());
    }

    private ChannelOrder saveOrder(String platform, String orderNo, Long customerId, Long whId,
                                   ChannelOrderStatus status, int attempts, LocalDateTime receivedAt, String error) {
        ChannelOrder o = new ChannelOrder();
        o.setPlatform(platform);
        o.setPlatformOrderNo(orderNo);
        o.setCustomerId(customerId);
        o.setWarehouseId(whId);
        o.setStatus(status);
        o.setAttempts(attempts);
        o.setOrderedAt(receivedAt.minusMinutes(5));
        o.setReceivedAt(receivedAt);
        o.setUpdatedAt(receivedAt);
        o.setError(error);
        return orderRepo.saveAndFlush(o);
    }

    private void saveLine(Long orderId, String sku, Long itemId, BigDecimal qty) {
        ChannelOrderLine l = new ChannelOrderLine();
        l.setOrderId(orderId);
        l.setSku(sku);
        l.setItemId(itemId);
        l.setQty(qty);
        lineRepo.saveAndFlush(l);
    }

    private void saveLog(String direction, String platform, String action, String ref,
                         boolean success, String message, LocalDateTime createdAt) {
        IntegrationLog l = new IntegrationLog();
        l.setDirection(direction);
        l.setPlatform(platform);
        l.setAction(action);
        l.setRef(ref);
        l.setSuccess(success);
        l.setMessage(message);
        l.setCreatedAt(createdAt);
        logRepo.saveAndFlush(l);
    }

    private static int indexOfOrder(JsonNode arr, String orderNo) {
        for (int i = 0; i < arr.size(); i++) {
            if (orderNo.equals(arr.get(i).get("platformOrderNo").asText())) {
                return i;
            }
        }
        return -1;
    }
}
