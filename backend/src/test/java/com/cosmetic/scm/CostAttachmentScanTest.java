package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.cosmetic.scm.analytics.AnalyticsService;
import com.cosmetic.scm.attachment.AttachmentRepository;
import com.cosmetic.scm.attachment.AttachmentService;
import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.demo.DemoDataLoader;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.MoStatus;
import com.cosmetic.scm.domain.MoType;
import com.cosmetic.scm.domain.OwnerType;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.inventory.ScanService;
import com.cosmetic.scm.master.MasterService;
import com.cosmetic.scm.planning.PlanningService;
import com.cosmetic.scm.production.MfgOrder;
import com.cosmetic.scm.production.MfgOrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** 成本与金额、附件、仓库扫码。与其它集成测试共用示例数据。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CostAttachmentScanTest {

    private static final byte[] PDF = "%PDF-1.4\n% 测试 COA\n".getBytes(StandardCharsets.UTF_8);

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    MasterService master;
    @Autowired
    AnalyticsService analytics;
    @Autowired
    PlanningService planning;
    @Autowired
    AttachmentService attachments;
    @Autowired
    AttachmentRepository attachmentRepo;
    @Autowired
    ScanService scan;
    @Autowired
    AuditService audit;
    @Autowired
    BatchRepository batches;
    @Autowired
    MfgOrderRepository mfgOrders;
    @Value("${scm.storage.dir}")
    String storageDir;

    @Test
    void finishedGoodCostRollsUpFromBomAndFeedsMoney() {
        // 精华液没填标准成本，按 BOM 估算：烟酰胺 0.0015×180 + 甘油 0.0015×12 + 透明质酸钠 0.00003×3600
        //   + 苯氧乙醇 0.00024×45 + 瓶 2.8 + 盒 1.2 = 4.4068
        Long serum = master.listItems().stream().filter(i -> i.getCode().equals("FG-SER-001")).findFirst().orElseThrow().getId();
        assertThat(master.getBom(serum).materialCost()).isEqualByComparingTo("4.4068");
        assertThat(master.getBom(serum).costComplete()).isTrue();
        assertThat(master.unitCosts().get(serum)).isEqualByComparingTo("4.4068");

        AnalyticsService.Money m = analytics.kpi().money();
        assertThat(m.fgValue()).isPositive();
        assertThat(m.materialValue()).isPositive();
        assertThat(m.itemsWithoutCost()).isZero();

        PlanningService.FgRow row = planning.finishedGoods(new PlanningService.PlanParams(90, 30, 15, 60)).stream()
                .filter(r -> r.code().equals("FG-SER-001")).findFirst().orElseThrow();
        assertThat(row.unitCost()).isEqualByComparingTo("4.4068");
        assertThat(row.listPrice()).isEqualByComparingTo("199");
    }

    @Test
    @WithMockUser(username = "qa1")
    void attachmentsKeepIntegrityAndRejectForgedFiles() throws Exception {
        Batch b = batches.findByStatusInOrderByIdDesc(List.of(BatchStatus.RELEASED)).get(0);
        AttachmentService.AttachmentView a = attachments.upload(OwnerType.BATCH, b.getId(),
                new MockMultipartFile("file", "COA-测试.pdf", "application/pdf", PDF), "供应商 COA");
        assertThat(attachments.list(OwnerType.BATCH, b.getId())).anyMatch(x -> x.id().equals(a.id()));
        assertThat(attachments.download(a.id()).bytes()).isEqualTo(PDF);

        // 改了扩展名的可执行文件、不在白名单的类型都拒收
        assertThatThrownBy(() -> attachments.upload(OwnerType.BATCH, b.getId(),
                new MockMultipartFile("file", "报告.pdf", "application/pdf", "MZ\u0090\u0000".getBytes(StandardCharsets.ISO_8859_1)), null))
                .hasMessageContaining("和扩展名");
        assertThatThrownBy(() -> attachments.upload(OwnerType.BATCH, b.getId(),
                new MockMultipartFile("file", "tool.exe", "application/octet-stream", new byte[] {1, 2}), null))
                .hasMessageContaining("不支持的文件类型");

        // 文件在磁盘上被替换：下载时校验不一致，拒绝
        AttachmentService.AttachmentView b2 = attachments.upload(OwnerType.BATCH, b.getId(),
                new MockMultipartFile("file", "report.pdf", "application/pdf", PDF), null);
        String key = attachmentRepo.findById(b2.id()).orElseThrow().getStorageKey();
        Files.write(Path.of(storageDir).resolve(key), "%PDF-1.4 tampered".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> attachments.download(b2.id())).hasMessageContaining("校验不一致");

        // 文件丢失：给出明确提示，不是笼统的系统错误
        Files.delete(Path.of(storageDir).resolve(key));
        assertThatThrownBy(() -> attachments.download(b2.id())).hasMessageContaining("读取失败");
    }

    @Test
    void oemPortalAttachmentsStayWithinOwnOrders() throws Exception {
        String oem = login("oem1");
        String dealer = login("dealer1");
        MfgOrder own = mfgOrders.findAll().stream()
                .filter(m -> m.getMoType() == MoType.OEM && m.getStatus() != MoStatus.COMPLETED).findFirst().orElseThrow();
        MfgOrder done = mfgOrders.findAll().stream()
                .filter(m -> m.getMoType() == MoType.OEM && m.getStatus() == MoStatus.COMPLETED).findFirst().orElseThrow();
        MfgOrder notOem = mfgOrders.findAll().stream().filter(m -> m.getMoType() == MoType.OWN).findFirst().orElseThrow();
        MockMultipartFile file = new MockMultipartFile("file", "出厂检验报告.pdf", "application/pdf", PDF);

        mvc.perform(multipart("/api/portal/oem/orders/" + own.getId() + "/attachments").file(file)
                .header("Authorization", "Bearer " + oem)).andExpect(status().isOk());
        // 已完工入库的单子不能再传，界面隐藏按钮之外服务端也要拦
        mvc.perform(multipart("/api/portal/oem/orders/" + done.getId() + "/attachments").file(file)
                .header("Authorization", "Bearer " + oem)).andExpect(status().is4xxClientError());
        mvc.perform(multipart("/api/portal/oem/orders/" + notOem.getId() + "/attachments").file(file)
                .header("Authorization", "Bearer " + oem)).andExpect(status().isNotFound());
        mvc.perform(get("/api/portal/oem/orders/" + own.getId() + "/attachments")
                .header("Authorization", "Bearer " + dealer)).andExpect(status().isForbidden());
        mvc.perform(get("/api/attachments").param("ownerType", "MFG_ORDER").param("ownerId", own.getId().toString())
                .header("Authorization", "Bearer " + oem)).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "prod1")
    void scanCardFlagsUnusableBatchesAndChecksAreAudited() {
        Batch released = batches.findByStatusInOrderByIdDesc(List.of(BatchStatus.RELEASED)).stream()
                .filter(b -> b.getBatchNo().startsWith("A3-")).reduce((a, c) -> a.getId() < c.getId() ? a : c).orElseThrow();
        Batch quarantine = batches.findByStatusInOrderByIdDesc(List.of(BatchStatus.QUARANTINE)).get(0);

        ScanService.BatchCard ok = scan.card(released.getBatchNo());
        assertThat(ok.stock()).isNotEmpty();
        // 天猫退回的 20 瓶在隔离区，扫码会提示
        assertThat(ok.warnings()).anyMatch(w -> w.contains("隔离区"));
        assertThat(scan.card(quarantine.getBatchNo()).level()).isEqualTo("bad");

        ScanService.CheckResult r = scan.recordCheck(new ScanService.CheckReq("MO", "MO-TEST-01",
                List.of("R-1", "R-2"), List.of("R-1", "R-9")));
        assertThat(r.passed()).isFalse();
        assertThat(r.missing()).containsExactly("R-2");
        assertThat(r.unexpected()).containsExactly("R-9");
        assertThat(audit.list("MO-TEST-01", 5)).anyMatch(l -> l.getAction().equals("发料扫码核对"));
    }

    @Test
    void wrongContentTypeIsAClientErrorNotAServerFault() throws Exception {
        String sales = login("sales1");
        mvc.perform(post("/api/attachments").contentType(MediaType.APPLICATION_JSON).content("{}")
                .header("Authorization", "Bearer " + sales)).andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/api/items").contentType(MediaType.TEXT_PLAIN).content("x")
                .header("Authorization", "Bearer " + sales)).andExpect(status().isUnsupportedMediaType());
    }

    private String login(String username) throws Exception {
        String resp = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", DemoDataLoader.DEMO_PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(resp).get("token").asText();
    }
}
