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

import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.Decision;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.inventory.OpeningStockService;
import com.cosmetic.scm.master.MasterDtos;
import com.cosmetic.scm.master.MasterService;
import com.cosmetic.scm.quality.QualityService;
import com.cosmetic.scm.security.UserRepository;
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

/** V59 不合格品与冻结批次处置闭环台账及报表导出集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-v59;MODE=MySQL;DB_CLOSE_DELAY=-1")
class V59NcrDispositionClosureTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MasterService master;
    @Autowired OpeningStockService opening;
    @Autowired QualityService quality;
    @Autowired BatchRepository batches;
    @Autowired UserRepository users;

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
    void rejectedBatchTracksDispositionClosureAndExportsReport() throws Exception {
        LocalDate today = LocalDate.now();
        as("admin", () -> {
            master.createPartner(new MasterDtos.PartnerReq(
                    "SUP-V59", "V59 原料供应商", PartnerKind.SUPPLIER, null, null, null, null, null, 0, null, null, true, false));
            master.createItem(new MasterDtos.ItemReq(
                    "RM-V59", "V59 测试香精", ItemType.RM, "kg", null, null, false, 24,
                    null, null, true, new BigDecimal("80.00"), null));
            return null;
        });
        as("qso1", () -> {
            var row = new OpeningStockService.OpeningRow(
                    "RM-V59", "LOT-V59-REJ", "EXT-V59", today.minusDays(5), today.plusMonths(12),
                    "WH-MAIN", new BigDecimal("10"), BatchStatus.QUARANTINE, null, null, null, null);
            assertThat(opening.run(new OpeningStockService.OpeningReq(List.of(row), false, null),
                    users.findByUsername("qso1").orElseThrow()).errors()).isEmpty();
            return null;
        });
        Batch batch = batches.findByBatchNo("LOT-V59-REJ").orElseThrow();

        // 1. 来料检验判不合格
        as("qa1", () -> quality.materialDecision(batch.getId(), new QualityService.MaterialDecisionReq(
                Decision.REJECTED, "QC-V59-01", "气味异常判定不合格", PWD), users.findByUsername("qa1").orElseThrow()));

        String qaToken = login("qa1");
        JsonNode list = json.readTree(mvc.perform(get("/api/quality/ncr-dispositions")
                        .header("Authorization", "Bearer " + qaToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode row = findByBatchNo(list, "LOT-V59-REJ");
        assertThat(row).isNotNull();
        assertThat(row.get("batchStatus").asText()).isEqualTo("REJECTED");
        assertThat(row.get("onHand").decimalValue()).isEqualByComparingTo("10");
        assertThat(row.get("closureState").asText()).isEqualTo("PENDING");
        assertThat(row.get("reason").asText()).contains("气味异常");

        // 2. 导出不合格品处置闭环台账 Excel
        byte[] xlsx = mvc.perform(get("/api/reports/ncr-disposition").header("Authorization", "Bearer " + qaToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(xlsx.length).isGreaterThan(100);
    }

    private static JsonNode findByBatchNo(JsonNode array, String batchNo) {
        for (JsonNode n : array) {
            if (batchNo.equals(n.get("batchNo").asText())) {
                return n;
            }
        }
        return null;
    }
}
