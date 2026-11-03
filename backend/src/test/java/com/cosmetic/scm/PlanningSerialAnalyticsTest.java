package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.cosmetic.scm.analytics.AnalyticsService;
import com.cosmetic.scm.master.ItemRepository;
import com.cosmetic.scm.master.PartnerRepository;
import com.cosmetic.scm.planning.PlanningService;
import com.cosmetic.scm.serial.SerialCodeRepository;
import com.cosmetic.scm.serial.SerialService;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** 补货建议、一物一码、经营看板和健康检查。与其它集成测试共用示例数据，改数据的用例回滚。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlanningSerialAnalyticsTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    PlanningService planning;
    @Autowired
    SerialService serials;
    @Autowired
    SerialCodeRepository codes;
    @Autowired
    AnalyticsService analytics;
    @Autowired
    ItemRepository items;
    @Autowired
    PartnerRepository partners;

    @Test
    void finishedGoodsPlanUsesShipmentsAndPipeline() {
        List<PlanningService.FgRow> rows = planning.finishedGoods(new PlanningService.PlanParams(90, 30, 15, 60));
        PlanningService.FgRow serum = rows.stream().filter(r -> r.code().equals("FG-SER-001")).findFirst().orElseThrow();
        // 示例里出库：京东 2000 + 天猫 1000
        assertThat(serum.shipped()).isEqualByComparingTo("3000");
        assertThat(serum.dailyDemand()).isPositive();
        assertThat(serum.wip()).isEqualByComparingTo("3000");        // 等受托厂申报的委托单
        assertThat(serum.factoryName()).contains("禾川");
        PlanningService.FgRow cream = rows.stream().filter(r -> r.code().equals("FG-CRM-002")).findFirst().orElseThrow();
        assertThat(cream.suggested()).isZero();                       // 没有历史出库，不建议补货
    }

    @Test
    void materialPlanNetsOffStockAndOpenOrders() {
        Long cream = items.findAll().stream().filter(i -> i.getCode().equals("FG-CRM-002")).findFirst().orElseThrow().getId();
        Long own = partners.findByCode("OWN-01").orElseThrow().getId();
        List<PlanningService.MaterialRow> rows = planning.materials(new PlanningService.MaterialReq(
                List.of(new PlanningService.PlanLine(cream, new BigDecimal("8000"), own))));
        PlanningService.MaterialRow squ = rows.stream().filter(r -> r.code().equals("RM-SQU-005")).findFirst().orElseThrow();
        // 新计划 40 kg + 已计划未发料的面霜单 40 kg，在库 60 kg → 还差 20 kg
        assertThat(squ.gross()).isEqualByComparingTo("40");
        assertThat(squ.openOrders()).isEqualByComparingTo("40");
        assertThat(squ.suggested()).isEqualByComparingTo("20");
        assertThat(squ.supplierName()).contains("华澄");
        assertThat(rows).noneMatch(r -> r.suggested().signum() < 0);
    }

    @Test
    @Transactional
    @WithMockUser(username = "qa1")
    void serialCodesVerifyAsGenuineThenRepeatedAndCapAtBatchQty() throws Exception {
        SerialService.BatchCodes demo = serials.batches().stream().filter(b -> b.generated() > 0).findFirst().orElseThrow();
        String code = codes.findByBatchIdOrderBySeq(demo.batchId()).get(0).getCode();

        // 公开接口不需要登录；输入带短横线和小写也能查
        String body = json.writeValueAsString(Map.of("code", code.substring(0, 4).toLowerCase() + "-" + code.substring(4)));
        mvc.perform(post("/api/public/verify").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("GENUINE"));
        mvc.perform(post("/api/public/verify").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.status").value("REPEATED")).andExpect(jsonPath("$.scanCount").value(2));
        mvc.perform(post("/api/public/verify").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("code", "0000-0000-0000"))))
                .andExpect(jsonPath("$.status").value("NOT_FOUND"));

        BigDecimal remaining = demo.qtyIn().subtract(BigDecimal.valueOf(demo.generated()));
        int tooMany = Math.min(remaining.intValue() + 1, SerialService.MAX_PER_REQUEST);
        if (tooMany > remaining.intValue()) {
            assertThatThrownBy(() -> serials.generate(new SerialService.GenerateReq(demo.batchNo(), tooMany)))
                    .hasMessageContaining("最多还能生成");
        }
        assertThat(serials.generate(new SerialService.GenerateReq(demo.batchNo(), 5)).generated()).isEqualTo(demo.generated() + 5);
        assertThat(serials.exportCsv(demo.batchId())).contains("扫码链接").contains(code.substring(0, 4) + "-");
    }

    @Test
    void kpiCoversShelfLifeSlowMovingAndTraceSpeed() {
        AnalyticsService.Kpi k = analytics.kpi();
        assertThat(k.shelf()).anyMatch(s -> s.itemName().contains("精华液"));
        // 14 个月前生产的老精华液，近 60 天没有出库，算呆滞
        assertThat(k.slowMoving()).anyMatch(s -> s.itemName().contains("精华液"));
        assertThat(k.factories()).anyMatch(f -> f.factoryName().contains("禾川") && f.completed() >= 2);
        assertThat(k.trace().sampled()).isPositive();
        assertThat(k.trace().maxMs()).isLessThan(k.trace().targetMs());
        assertThat(k.complaints().monthly()).hasSize(6);
    }

    @Test
    void healthIsPublicAndReportsDatabase() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.database").value("UP"));
    }
}
