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
import com.cosmetic.scm.sourcing.Rfq;
import com.cosmetic.scm.sourcing.RfqRepository;
import com.cosmetic.scm.sourcing.RfqService;
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

/** V75 询价比价（RFQ）截止倒计时、超期未定标监控看板、价格权限隔离及报表导出集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-v75;MODE=MySQL;DB_CLOSE_DELAY=-1")
class V75RfqClosureBoardTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TimeProvider time;
    @Autowired MasterService master;
    @Autowired RfqService rfqService;
    @Autowired RfqRepository rfqRepo;

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
    void closureBoardTracksOverdueAndDueSoonRfqsAndEnforcesPriceAccess() throws Exception {
        LocalDate today = time.today();
        var ctx = as("admin", () -> {
            Long supId = master.createPartner(new MasterDtos.PartnerReq(
                    "SUP-V75", "V75 原料供应商", PartnerKind.SUPPLIER, "LIC-V75", today.plusYears(2),
                    null, null, null, 0, "周经理 13600136000", null, true, false)).id();
            Item rm = master.createItem(new MasterDtos.ItemReq(
                    "RM-V75", "V75 玻色因原料", ItemType.RM, "kg", null, null, false, 24,
                    null, null, true, new BigDecimal("320"), null));
            return new long[] {supId, rm.getId()};
        });

        // 1) 超期未定标（截止日期调整为 5 天前） -> OVERDUE
        var overdueRfq = as("purchase1", () -> rfqService.create(new RfqService.CreateRfqReq(
                "V75 玻色因紧急询价", today.plusDays(1), List.of(ctx[0]),
                List.of(new RfqService.RfqLineReq(ctx[1], new BigDecimal("50"))))));
        Rfq overdueEntity = rfqRepo.findById(overdueRfq.id()).orElseThrow();
        overdueEntity.setDeadline(today.minusDays(5));
        rfqRepo.save(overdueEntity);

        // 2) 2 天后截止且 0 报价 -> DUE_SOON 且计入 lowResponseCount
        var dueSoonRfq = as("purchase1", () -> rfqService.create(new RfqService.CreateRfqReq(
                "V75 季度补货询价", today.plusDays(2), List.of(ctx[0]),
                List.of(new RfqService.RfqLineReq(ctx[1], new BigDecimal("30"))))));

        String purToken = login("purchase1");
        JsonNode board = json.readTree(mvc.perform(get("/api/rfqs/closure-board")
                        .header("Authorization", "Bearer " + purToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(board.get("overdueAwardCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(board.get("dueSoonCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(board.get("lowResponseCount").asInt()).isGreaterThanOrEqualTo(1);

        JsonNode overRow = findByRfqNo(board.get("rows"), overdueRfq.rfqNo());
        JsonNode soonRow = findByRfqNo(board.get("rows"), dueSoonRfq.rfqNo());
        assertThat(overRow).isNotNull();
        assertThat(overRow.get("riskLevel").asText()).isEqualTo("OVERDUE");
        assertThat(overRow.get("daysLeft").asLong()).isEqualTo(-5L);
        assertThat(overRow.get("canAward").asBoolean()).isTrue();

        assertThat(soonRow).isNotNull();
        assertThat(soonRow.get("riskLevel").asText()).isEqualTo("DUE_SOON");
        assertThat(soonRow.get("daysLeft").asLong()).isEqualTo(2L);

        // 采购可导出 Excel，非价格权限角色（生产岗位）必须 403 拦截
        byte[] xlsx = mvc.perform(get("/api/reports/rfq-closure").header("Authorization", "Bearer " + purToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(xlsx.length).isGreaterThan(100);

        String prodToken = login("prod1");
        mvc.perform(get("/api/rfqs/closure-board").header("Authorization", "Bearer " + prodToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/reports/rfq-closure").header("Authorization", "Bearer " + prodToken))
                .andExpect(status().isForbidden());
    }

    private static JsonNode findByRfqNo(JsonNode arr, String rfqNo) {
        for (JsonNode n : arr) {
            if (rfqNo.equals(n.get("rfqNo").asText())) {
                return n;
            }
        }
        return null;
    }
}
