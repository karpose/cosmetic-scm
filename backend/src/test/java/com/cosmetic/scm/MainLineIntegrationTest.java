package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.inventory.InventoryService;
import com.cosmetic.scm.dashboard.DashboardService;
import com.cosmetic.scm.trace.TraceService;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 启动时示例数据会通过真实服务走完一遍主线；这里校验结果和权限边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MainLineIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    BatchRepository batches;
    @Autowired
    InventoryService inventory;
    @Autowired
    TraceService trace;
    @Autowired
    DashboardService dashboard;

    @Test
    void demoMainLineIsConsistent() {
        DashboardService.Summary s = dashboard.summary();
        assertThat(s.counts().quarantine()).isEqualTo(1);
        assertThat(s.counts().pendingRelease()).isEqualTo(1);
        assertThat(s.counts().wipOrders()).isEqualTo(1);

        Batch released = batches.findAll().stream()
                .filter(b -> b.getBatchNo().startsWith("A3-") && b.getStatus() == BatchStatus.RELEASED)
                .findFirst().orElseThrow();
        assertThat(inventory.onHand(released.getId())).isEqualByComparingTo("6950");

        TraceService.TraceView t = trace.trace(released.getBatchNo());
        assertThat(t.shipments()).hasSize(2);
        assertThat(t.upstream()).anyMatch(u -> "工厂代采".equals(u.supplyLabel()) && u.extBatchNo() != null);
        assertThat(t.upstream()).anyMatch(u -> "品牌供料".equals(u.supplyLabel()) && u.batchNo() != null);
        assertThat(t.decisions()).isNotEmpty();
    }

    @Test
    void apiRequiresLogin() throws Exception {
        mvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyQualitySafetyOfficerCanMarketRelease() throws Exception {
        Batch pending = batches.findByStatusInOrderByIdDesc(java.util.List.of(BatchStatus.PENDING_RELEASE)).get(0);
        String body = json.writeValueAsString(Map.of(
                "decision", "RELEASED", "reportNo", "QC-T", "note", "test", "oemReleaseReviewed", true,
                "inspectionPassed", true, "recordsComplete", true, "consistentWithFiling", true,
                "retainQty", 6, "retainLocation", "A-1", "password", DemoDataLoader.DEMO_PASSWORD));
        String url = "/api/quality/batches/" + pending.getId() + "/market-release";

        mvc.perform(post(url).header("Authorization", "Bearer " + login("qa1"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        String wrongPassword = body.replace(DemoDataLoader.DEMO_PASSWORD, "wrong-password");
        mvc.perform(post(url).header("Authorization", "Bearer " + login("qso1"))
                        .contentType(MediaType.APPLICATION_JSON).content(wrongPassword))
                .andExpect(status().isBadRequest());
    }

    private String login(String username) throws Exception {
        String resp = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", DemoDataLoader.DEMO_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("token").asText();
    }
}
