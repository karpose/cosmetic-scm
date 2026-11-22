package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.ReturnDisposition;
import com.cosmetic.scm.domain.WarehouseKind;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.inventory.OpeningStockService;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterDtos;
import com.cosmetic.scm.master.MasterService;
import com.cosmetic.scm.sales.SalesService;
import com.cosmetic.scm.salesreturn.ReturnService;
import com.cosmetic.scm.security.UserRepository;
import com.cosmetic.scm.settlement.ArSettlementService;
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

/** V60 客退隔离处置与客户应收冲减闭环联动集成测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:scm-v60;MODE=MySQL;DB_CLOSE_DELAY=-1")
class V60ReturnRecallArClosureTest {

    private static final String PWD = DemoDataLoader.DEMO_PASSWORD;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TimeProvider time;
    @Autowired MasterService master;
    @Autowired OpeningStockService opening;
    @Autowired SalesService sales;
    @Autowired ReturnService returns;
    @Autowired ArSettlementService arSettlement;
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
    void salesReturnTracksDispositionAndArStatementOffsetClosure() throws Exception {
        LocalDate today = time.today();
        Long ownWh = master.listWarehouses().stream().filter(w -> "WH-MAIN".equals(w.code())).findFirst().orElseThrow().id();
        Long rtWh = master.listWarehouses().stream().filter(w -> w.kind() == WarehouseKind.RETURN).findFirst().orElseThrow().id();

        var ctx = as("admin", () -> {
            Long cusId = master.createPartner(new MasterDtos.PartnerReq(
                    "CUS-V60", "V60 测试客户", PartnerKind.CUSTOMER, null, null, null, null, "线下", 0, null, null, true, false)).id();
            Item fg = master.createItem(new MasterDtos.ItemReq(
                    "FG-V60", "V60 洁面乳", ItemType.FG, "瓶", null, null, false, 36,
                    "备案-V60", new BigDecimal("100"), true, new BigDecimal("30"), new BigDecimal("100")));
            return new long[] {cusId, fg.getId()};
        });
        long cusId = ctx[0];
        long fgId = ctx[1];

        as("qso1", () -> {
            var row = new OpeningStockService.OpeningRow(
                    "FG-V60", "LOT-V60-FG", null, today.minusDays(10), today.plusMonths(30),
                    "WH-MAIN", new BigDecimal("50"), BatchStatus.RELEASED, null, "RPT-V60", 2, "留样柜");
            assertThat(opening.run(new OpeningStockService.OpeningReq(List.of(row), false, null),
                    users.findByUsername("qso1").orElseThrow()).errors()).isEmpty();
            return null;
        });
        Batch fgBatch = batches.findByBatchNo("LOT-V60-FG").orElseThrow();

        // 1. 销售出库 20 瓶 → 退货入隔离区 5 瓶
        as("sales1", () -> sales.create(new SalesService.SoReq(cusId, ownWh,
                List.of(new SalesService.SoLineReq(fgId, new BigDecimal("20"))))));
        var ret = as("sales1", () -> returns.create(new ReturnService.ReturnReq(cusId, rtWh, null, "外盒压损",
                List.of(new ReturnService.ReturnLineReq(fgBatch.getId(), new BigDecimal("5"))))));
        Long lineId = ret.lines().get(0).id();

        String salesToken = login("sales1");

        // 2. 质检处置转可售 → 此时属于“已处置待冲减应收”
        as("qa1", () -> returns.dispose(lineId, new ReturnService.DisposeReq(
                ReturnDisposition.RESTOCK, ownWh, "复检完好转可售", PWD), users.findByUsername("qa1").orElseThrow()));

        mvc.perform(get("/api/sales-returns/closure-summary").header("Authorization", "Bearer " + salesToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disposedUnbilledArLines").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        // 3. 生成客户应收对账单（自动冲减已处置退货）→ 退货行回显 arStatementNo，unbilledArLines 归零
        var arSt = as("finance1", () -> arSettlement.create(new ArSettlementService.ArCreateReq(
                cusId, today, 30, BigDecimal.ZERO, null, Map.of(fgId, new BigDecimal("100")))));

        JsonNode list = json.readTree(mvc.perform(get("/api/sales-returns").header("Authorization", "Bearer " + salesToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode matched = findByReturnNo(list, ret.returnNo());
        assertThat(matched).isNotNull();
        assertThat(matched.get("unbilledArLines").asLong()).isZero();
        assertThat(matched.get("lines").get(0).get("arStatementNo").asText()).isEqualTo(arSt.statementNo());
    }

    private static JsonNode findByReturnNo(JsonNode array, String returnNo) {
        for (JsonNode n : array) {
            if (returnNo.equals(n.get("returnNo").asText())) {
                return n;
            }
        }
        return null;
    }
}
