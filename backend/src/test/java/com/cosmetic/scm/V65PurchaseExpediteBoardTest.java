package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterDtos;
import com.cosmetic.scm.master.MasterService;
import com.cosmetic.scm.purchase.PurchaseOrder;
import com.cosmetic.scm.purchase.PurchaseOrderRepository;
import com.cosmetic.scm.purchase.PurchaseService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** V65 采购在途催货与交付逾期追踪看板及报表导出集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-v65;MODE=MySQL;DB_CLOSE_DELAY=-1")
class V65PurchaseExpediteBoardTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TimeProvider time;
    @Autowired MasterService master;
    @Autowired PurchaseService purchase;
    @Autowired PurchaseOrderRepository poRepo;

    private <T> T as(String user, Supplier<T> s) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        try {
            return s.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private String login(String u) throws Exception {
        return json.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", u, "password", PWD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void expediteBoardClassifiesOverdueAndDueSoonOrdersAndExportsReport() throws Exception {
        LocalDate today = time.today();
        var ctx = as("admin", () -> {
            Long supId = master.createPartner(new MasterDtos.PartnerReq(
                    "SUP-V65", "V65 原料商", PartnerKind.SUPPLIER, "LIC-V65", today.plusYears(2),
                    null, null, null, 0, "张经理 13800138000", null, true, false)).id();
            Item rm = master.createItem(new MasterDtos.ItemReq(
                    "RM-V65", "V65 神经酰胺", ItemType.RM, "kg", null, null, false, 24,
                    null, null, true, new BigDecimal("150"), null));
            return new long[] {supId, rm.getId()};
        });

        var poLate = as("purchase1", () -> purchase.create(new PurchaseService.CreatePoReq(
                ctx[0], List.of(new PurchaseService.PoLineReq(ctx[1], new BigDecimal("20"))), today.plusDays(1))));
        var poSoon = as("purchase1", () -> purchase.create(new PurchaseService.CreatePoReq(
                ctx[0], List.of(new PurchaseService.PoLineReq(ctx[1], new BigDecimal("15"))), today.plusDays(5))));

        // 将 poLate 期望到货日调整为 3 天前模拟已超期
        PurchaseOrder lateEntity = poRepo.findById(poLate.id()).orElseThrow();
        lateEntity.setExpectedDate(today.minusDays(3));
        poRepo.save(lateEntity);

        String token = login("purchase1");
        JsonNode board = json.readTree(mvc.perform(get("/api/purchase-orders/expedite")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        JsonNode lateRow = findByPoNo(board, poLate.poNo());
        JsonNode soonRow = findByPoNo(board, poSoon.poNo());
        assertThat(lateRow).isNotNull();
        assertThat(lateRow.get("riskLevel").asText()).isEqualTo("OVERDUE");
        assertThat(lateRow.get("daysLeft").asLong()).isEqualTo(-3L);
        assertThat(lateRow.get("remainingQty").decimalValue()).isEqualByComparingTo("20");

        assertThat(soonRow).isNotNull();
        assertThat(soonRow.get("riskLevel").asText()).isEqualTo("DUE_SOON");
        assertThat(soonRow.get("daysLeft").asLong()).isEqualTo(5L);

        byte[] xlsx = mvc.perform(get("/api/reports/purchase-expedite").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(xlsx.length).isGreaterThan(100);
    }

    private static JsonNode findByPoNo(JsonNode arr, String poNo) {
        for (JsonNode n : arr) {
            if (poNo.equals(n.get("poNo").asText())) {
                return n;
            }
        }
        return null;
    }
}
