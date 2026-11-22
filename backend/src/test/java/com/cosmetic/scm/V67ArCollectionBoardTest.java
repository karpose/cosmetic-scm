package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.master.MasterDtos;
import com.cosmetic.scm.master.MasterService;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.PartnerRepository;
import com.cosmetic.scm.settlement.CustomerStatement;
import com.cosmetic.scm.settlement.CustomerStatementRepository;
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

/** V67 客户应收逾期催收与信用风控看板、角色权限隔离及报表导出集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-v67;MODE=MySQL;DB_CLOSE_DELAY=-1")
class V67ArCollectionBoardTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MasterService master;
    @Autowired PartnerRepository partners;
    @Autowired CustomerStatementRepository arStatements;

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
    void collectionBoardClassifiesCriticalAndDueSoonAndEnforcesArAccess() throws Exception {
        LocalDate today = LocalDate.now();
        var custIds = as("admin", () -> {
            Long c1 = master.createPartner(new MasterDtos.PartnerReq(
                    "CUST-V67-A", "V67 超额逾期客户", PartnerKind.CUSTOMER, null, null,
                    null, null, "KA", 50, "李财务 13900139000", null, true, false)).id();
            Long c2 = master.createPartner(new MasterDtos.PartnerReq(
                    "CUST-V67-B", "V67 临期客户", PartnerKind.CUSTOMER, null, null,
                    null, null, "线上", 50, "王财务 13700137000", null, true, false)).id();
            Partner p1 = partners.findById(c1).orElseThrow();
            p1.setCreditLimit(new BigDecimal("10000.00"));
            partners.save(p1);
            return new long[] {c1, c2};
        });

        // 1) 严重逾期 + 授信超额应收单（逾期 40 天，金额 12000 > 授信 10000）
        CustomerStatement sCrit = new CustomerStatement();
        sCrit.setStatementNo("AR-V67-CRIT");
        sCrit.setCustomerId(custIds[0]);
        sCrit.setPeriodTo(today.minusDays(70));
        sCrit.setCreditDays(30);
        sCrit.setDueDate(today.minusDays(40));
        sCrit.setStatus("APPROVED");
        sCrit.setGoodsAmount(new BigDecimal("12000.00"));
        sCrit.setTaxAmount(new BigDecimal("1380.53"));
        sCrit.setTotalAmount(new BigDecimal("12000.00"));
        sCrit.setReceivedAmount(BigDecimal.ZERO);
        sCrit.setCreatedBy("sales1");
        sCrit.setCreatedAt(LocalDateTime.now().minusDays(70));
        sCrit.setApprovedBy("finance1");
        sCrit.setApprovedAt(LocalDateTime.now().minusDays(69));
        arStatements.save(sCrit);

        // 2) 7天内到期应收单（距到期 3 天）
        CustomerStatement sSoon = new CustomerStatement();
        sSoon.setStatementNo("AR-V67-SOON");
        sSoon.setCustomerId(custIds[1]);
        sSoon.setPeriodTo(today.minusDays(27));
        sSoon.setCreditDays(30);
        sSoon.setDueDate(today.plusDays(3));
        sSoon.setStatus("APPROVED");
        sSoon.setGoodsAmount(new BigDecimal("5000.00"));
        sSoon.setTaxAmount(new BigDecimal("575.22"));
        sSoon.setTotalAmount(new BigDecimal("5000.00"));
        sSoon.setReceivedAmount(BigDecimal.ZERO);
        sSoon.setCreatedBy("sales1");
        sSoon.setCreatedAt(LocalDateTime.now().minusDays(27));
        sSoon.setApprovedBy("finance1");
        sSoon.setApprovedAt(LocalDateTime.now().minusDays(26));
        arStatements.save(sSoon);

        String finToken = login("finance1");
        JsonNode board = json.readTree(mvc.perform(get("/api/ar-statements/collection-board")
                        .header("Authorization", "Bearer " + finToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(board.get("criticalCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(board.get("dueSoonCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(board.get("overLimitCustomerCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(board.get("totalOverdueAmount").decimalValue()).isGreaterThanOrEqualTo(new BigDecimal("12000.00"));

        JsonNode critRow = findByStatementNo(board.get("rows"), "AR-V67-CRIT");
        JsonNode soonRow = findByStatementNo(board.get("rows"), "AR-V67-SOON");
        assertThat(critRow).isNotNull();
        assertThat(critRow.get("riskLevel").asText()).isEqualTo("CRITICAL");
        assertThat(critRow.get("overdueDays").asLong()).isEqualTo(40L);
        assertThat(critRow.get("overLimit").asBoolean()).isTrue();
        assertThat(critRow.get("collectionAction").asText()).contains("授信超额");

        assertThat(soonRow).isNotNull();
        assertThat(soonRow.get("riskLevel").asText()).isEqualTo("DUE_SOON");
        assertThat(soonRow.get("overdueDays").asLong()).isEqualTo(0L);

        // 财务可导出应收催收 Excel，非 AR 角色（生产岗位）必须 403 拦截
        byte[] xlsx = mvc.perform(get("/api/reports/ar-collection").header("Authorization", "Bearer " + finToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(xlsx.length).isGreaterThan(100);

        String prodToken = login("prod1");
        mvc.perform(get("/api/ar-statements/collection-board").header("Authorization", "Bearer " + prodToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/reports/ar-collection").header("Authorization", "Bearer " + prodToken))
                .andExpect(status().isForbidden());
    }

    private static JsonNode findByStatementNo(JsonNode arr, String no) {
        for (JsonNode n : arr) {
            if (no.equals(n.get("statementNo").asText())) {
                return n;
            }
        }
        return null;
    }
}
