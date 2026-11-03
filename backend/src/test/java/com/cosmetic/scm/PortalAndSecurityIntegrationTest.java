package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.cosmetic.scm.dealer.DealerService;
import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.domain.DealerOrderStatus;
import com.cosmetic.scm.domain.MoStatus;
import com.cosmetic.scm.domain.OemReportStatus;
import com.cosmetic.scm.domain.SupplyMode;
import com.cosmetic.scm.master.WarehouseRepository;
import com.cosmetic.scm.oemportal.OemPortalService;
import com.cosmetic.scm.production.ProductionService;
import com.cosmetic.scm.security.UserRepository;
import com.cosmetic.scm.security.UserService;
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

/**
 * 账号安全、门户权限边界、代工厂 / 经销商门户和电商对接。与 MainLineIntegrationTest 共用同一套示例数据；
 * 会改数据的用例加 @Transactional 回滚。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PortalAndSecurityIntegrationTest {

    private static final String API_KEY = "test-integration-key";

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    UserRepository users;
    @Autowired
    UserService userService;
    @Autowired
    OemPortalService oemPortal;
    @Autowired
    ProductionService production;
    @Autowired
    DealerService dealers;
    @Autowired
    WarehouseRepository warehouses;

    @Test
    void portalAccountsOnlyReachTheirOwnPortal() throws Exception {
        String oem = login("oem1");
        String dealer = login("dealer1");
        String qa = login("qa1");

        mvc.perform(get("/api/dashboard").header("Authorization", "Bearer " + oem)).andExpect(status().isForbidden());
        mvc.perform(get("/api/stock").header("Authorization", "Bearer " + dealer)).andExpect(status().isForbidden());
        mvc.perform(get("/api/portal/dealer/catalog").header("Authorization", "Bearer " + oem)).andExpect(status().isForbidden());
        mvc.perform(get("/api/portal/oem/orders").header("Authorization", "Bearer " + qa)).andExpect(status().isForbidden());

        mvc.perform(get("/api/portal/oem/orders").header("Authorization", "Bearer " + oem)).andExpect(status().isOk());
        mvc.perform(get("/api/portal/dealer/catalog").header("Authorization", "Bearer " + dealer)).andExpect(status().isOk());
    }

    @Test
    void fiveWrongPasswordsLockTheAccount() throws Exception {
        for (int i = 1; i <= 4; i++) {
            mvc.perform(loginReq("viewer1", "wrong-" + i)).andExpect(status().isUnauthorized());
        }
        mvc.perform(loginReq("viewer1", "wrong-5")).andExpect(status().isLocked());
        // 锁定期间正确密码也进不去
        mvc.perform(loginReq("viewer1", DemoDataLoader.DEMO_PASSWORD)).andExpect(status().isLocked());

        userService.unlock(users.findByUsername("viewer1").orElseThrow().getId());
        mvc.perform(loginReq("viewer1", DemoDataLoader.DEMO_PASSWORD)).andExpect(status().isOk());
    }

    @Test
    void weakPasswordsAndMismatchedPortalPartnerAreRejected() throws Exception {
        String admin = login("admin", "Admin@123456");
        Long dealerPartnerId = users.findByUsername("dealer1").orElseThrow().getPartnerId();
        mvc.perform(post("/api/users").header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "weak1", "displayName", "弱密码",
                                "password", "abcdefgh", "roles", List.of("VIEWER")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("至少 3 类")));
        // 代工厂门户账号不能绑定客户
        mvc.perform(post("/api/users").header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "oem9", "displayName", "错绑",
                                "password", "Strong@2026", "roles", List.of("OEM_PORTAL"), "partnerId", dealerPartnerId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("只能绑定")));
    }

    @Test
    @Transactional
    @WithMockUser(username = "oem1")
    void oemReportIsValidatedAndAcceptedOnCompletion() {
        OemPortalService.OemOrderView mo = oemPortal.myOrders().stream()
                .filter(o -> o.status() == MoStatus.ISSUED).findFirst().orElseThrow();
        assertThat(mo.stageLabel()).isEqualTo("已排产");

        // 缺少代采原料批号：和品牌方入库同样的口径，提交不了
        OemPortalService.ReportReq missing = new OemPortalService.ReportReq(new BigDecimal("2990"), LocalDate.now().minusDays(1),
                "HC-A-04", "QC-HC-04", "禾川 QA 张三", null, List.of());
        assertThatThrownBy(() -> oemPortal.submitReport(mo.id(), missing)).hasMessageContaining("代采原料");

        List<OemPortalService.MaterialReq> mats = mo.requirements().stream()
                .filter(r -> r.supplyMode() == SupplyMode.OEM)
                .map(r -> new OemPortalService.MaterialReq(r.itemId(), "EXT-" + r.itemCode(), r.requiredQty())).toList();
        OemPortalService.ReportReq ok = new OemPortalService.ReportReq(new BigDecimal("2990"), LocalDate.now().minusDays(1),
                "HC-A-04", "QC-HC-04", "禾川 QA 张三", null, mats);
        oemPortal.submitReport(mo.id(), ok);
        assertThatThrownBy(() -> oemPortal.submitReport(mo.id(), ok)).hasMessageContaining("重复");

        production.complete(mo.id(), new ProductionService.CompleteReq(new BigDecimal("2990"), LocalDate.now().minusDays(1),
                "HC-A-04", mainWarehouseId(), true, null, mats.stream()
                .map(m -> new ProductionService.ExtMaterialReq(m.itemId(), m.extBatchNo(), m.qty())).toList()));
        assertThat(oemPortal.trail(mo.id()).reports()).singleElement()
                .extracting(OemPortalService.ReportView::status).isEqualTo(OemReportStatus.ACCEPTED);
    }

    @Test
    @Transactional
    @WithMockUser(username = "dealer1")
    void dealerOrderApprovalShipsByFefoAndShowsBatches() {
        assertThat(dealers.catalog()).anyMatch(c -> c.code().equals("FG-SER-001") && c.available().signum() > 0);
        DealerService.OrderView pending = dealers.myOrders().stream()
                .filter(o -> o.status() == DealerOrderStatus.SUBMITTED).findFirst().orElseThrow();

        DealerService.OrderView done = dealers.approve(pending.id(), new DealerService.ApproveReq(mainWarehouseId()));
        assertThat(done.status()).isEqualTo(DealerOrderStatus.APPROVED);
        assertThat(done.soNo()).isNotBlank();
        assertThat(done.shipped()).isNotEmpty();
        assertThat(dealers.myOrders()).anyMatch(o -> o.id().equals(pending.id()) && !o.shipped().isEmpty());
    }

    @Test
    @Transactional
    void integrationPushIsIdempotentAndKeyProtected() throws Exception {
        String body = json.writeValueAsString(Map.of("platform", "xhs", "customerCode", "CUS-SY", "orders", List.of(
                Map.of("orderNo", "XHS-T-1", "lines", List.of(Map.of("sku", "FG-SER-001", "qty", 1))))));

        mvc.perform(post("/api/integration/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/integration/orders").header("X-Api-Key", "wrong").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/integration/orders").header("X-Api-Key", API_KEY).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("SHIPPED"))
                .andExpect(jsonPath("$[0].duplicate").value(false));
        mvc.perform(post("/api/integration/orders").header("X-Api-Key", API_KEY).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$[0].duplicate").value(true));

        // 示例里推来的面霜订单因为没有库存出库失败，留待重试
        mvc.perform(get("/api/integration/stock").header("X-Api-Key", API_KEY).param("customerCode", "CUS-TM"))
                .andExpect(status().isOk());
        String admin = login("admin", "Admin@123456");
        mvc.perform(get("/api/channel-orders").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$[?(@.platformOrderNo == 'TM-DEMO-0001')].status").value(org.hamcrest.Matchers.hasItem("FAILED")));
    }

    private Long mainWarehouseId() {
        return warehouses.findAll().stream().filter(w -> "WH-MAIN".equals(w.getCode())).findFirst().orElseThrow().getId();
    }

    private org.springframework.test.web.servlet.RequestBuilder loginReq(String username, String password) throws Exception {
        return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", username, "password", password)));
    }

    private String login(String username) throws Exception {
        return login(username, DemoDataLoader.DEMO_PASSWORD);
    }

    private String login(String username, String password) throws Exception {
        String resp = mvc.perform(loginReq(username, password)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("token").asText();
    }
}
