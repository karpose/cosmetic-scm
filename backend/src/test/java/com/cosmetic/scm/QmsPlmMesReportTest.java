package com.cosmetic.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.common.XlsxWriter;
import com.cosmetic.scm.domain.AuditResult;
import com.cosmetic.scm.domain.AuditType;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.CalibrationResult;
import com.cosmetic.scm.domain.CapaSource;
import com.cosmetic.scm.domain.CapaStatus;
import com.cosmetic.scm.domain.CapaType;
import com.cosmetic.scm.domain.CaseStatus;
import com.cosmetic.scm.domain.ClearancePhase;
import com.cosmetic.scm.domain.Decision;
import com.cosmetic.scm.domain.DeviationArea;
import com.cosmetic.scm.domain.DeviationLevel;
import com.cosmetic.scm.domain.EquipmentStatus;
import com.cosmetic.scm.domain.FilingType;
import com.cosmetic.scm.domain.FormulaStatus;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.MoStatus;
import com.cosmetic.scm.domain.MoType;
import com.cosmetic.scm.domain.OemStage;
import com.cosmetic.scm.domain.PoStatus;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.RetainStatus;
import com.cosmetic.scm.domain.SourceType;
import com.cosmetic.scm.domain.StepStatus;
import com.cosmetic.scm.domain.rules.FormulaDiff;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.master.BomLine;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.ItemRepository;
import com.cosmetic.scm.master.MasterDtos.BomLineReq;
import com.cosmetic.scm.master.MasterDtos.BomReq;
import com.cosmetic.scm.master.MasterDtos.ItemReq;
import com.cosmetic.scm.master.MasterService;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.PartnerRepository;
import com.cosmetic.scm.mes.Equipment;
import com.cosmetic.scm.mes.EquipmentRepository;
import com.cosmetic.scm.mes.EquipmentService;
import com.cosmetic.scm.mes.MesService;
import com.cosmetic.scm.inventory.InventoryService;
import com.cosmetic.scm.inventory.OpeningStockService;
import com.cosmetic.scm.notify.NotificationLogRepository;
import com.cosmetic.scm.notify.NotifyService;
import com.cosmetic.scm.oemportal.OemProgress;
import com.cosmetic.scm.oemportal.OemProgressRepository;
import com.cosmetic.scm.planning.PlanningService;
import com.cosmetic.scm.plm.FilingService;
import com.cosmetic.scm.plm.FormulaService;
import com.cosmetic.scm.plm.FormulaVersionRepository;
import com.cosmetic.scm.production.MfgIssueRepository;
import com.cosmetic.scm.production.MfgOrder;
import com.cosmetic.scm.production.MfgOrderRepository;
import com.cosmetic.scm.production.ProductionService;
import com.cosmetic.scm.purchase.PurchaseService;
import com.cosmetic.scm.qms.DeviationService;
import com.cosmetic.scm.qms.RetainSample;
import com.cosmetic.scm.qms.RetainSampleRepository;
import com.cosmetic.scm.qms.RetainService;
import com.cosmetic.scm.qms.SupplierAuditService;
import com.cosmetic.scm.quality.QualityService;
import com.cosmetic.scm.report.ReportService;
import com.cosmetic.scm.sales.SalesOrder;
import com.cosmetic.scm.sales.SalesOrderRepository;
import com.cosmetic.scm.sales.SoLine;
import com.cosmetic.scm.sales.SoLineRepository;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.UserRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** 配方版本、质量体系、批生产记录、报表和提醒。会改数据的用例用事务回滚，不影响其它测试类。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"spring.mail.host=smtp.test.invalid", "scm.notify.mail-to=qa@test.invalid,qso@test.invalid"})
class QmsPlmMesReportTest {

    private static final String DEMO = "Demo@12345";

    @Autowired MockMvc mvc;
    @Autowired FormulaService formulas;
    @Autowired FormulaVersionRepository versions;
    @Autowired FilingService filings;
    @Autowired MasterService master;
    @Autowired ItemRepository items;
    @Autowired PartnerRepository partners;
    @Autowired UserRepository users;
    @Autowired BatchRepository batches;
    @Autowired MfgOrderRepository orders;
    @Autowired ProductionService production;
    @Autowired QualityService quality;
    @Autowired DeviationService deviations;
    @Autowired SupplierAuditService audits;
    @Autowired RetainService retains;
    @Autowired RetainSampleRepository retainSamples;
    @Autowired MesService mes;
    @Autowired ReportService reports;
    @Autowired NotifyService notify;
    @Autowired NotificationLogRepository notifyLogs;
    @Autowired TimeProvider time;
    @Autowired com.cosmetic.scm.dashboard.DashboardService dashboard;
    @Autowired OpeningStockService opening;
    @Autowired EquipmentService equipment;
    @Autowired EquipmentRepository equipmentRepo;
    @Autowired PurchaseService purchases;
    @Autowired MfgIssueRepository issues;
    @Autowired InventoryService inventory;
    @Autowired OemProgressRepository progress;
    @Autowired PlanningService planning;
    @Autowired SalesOrderRepository salesOrders;
    @Autowired SoLineRepository soLines;
    @MockBean JavaMailSender mail;

    private AppUser user(String name) {
        return users.findByUsername(name).orElseThrow();
    }

    private Item item(String code) {
        return items.findAll().stream().filter(i -> code.equals(i.getCode())).findFirst().orElseThrow();
    }

    private List<BomLineReq> linesFrom(Long fgId, String changeCode, String qty) {
        Item change = item(changeCode);
        return master.bomLines(fgId).stream().map((BomLine l) -> new BomLineReq(l.getItemId(),
                l.getItemId().equals(change.getId()) ? new BigDecimal(qty) : l.getQtyPer(), l.getSupplyMode())).toList();
    }

    // ---------- PLM ----------

    @Test
    void formulaDiffOnlyAsksForFilingWhenRawMaterialsChange() {
        var rm = new FormulaDiff.Line(1L, new BigDecimal("0.0010"), "BRAND", true);
        var pk = new FormulaDiff.Line(2L, BigDecimal.ONE, "BRAND", false);
        // 数值相等但小数位不同，不算变化
        assertThat(FormulaDiff.diff(List.of(rm, pk), List.of(new FormulaDiff.Line(1L, new BigDecimal("0.001"), "BRAND", true), pk))).isEmpty();
        // 只换包材、只改供料方式：不涉及配方
        var pkChanged = FormulaDiff.diff(List.of(rm, pk), List.of(rm, new FormulaDiff.Line(3L, BigDecimal.ONE, "BRAND", false)));
        assertThat(pkChanged).hasSize(2);
        assertThat(FormulaDiff.needsFiling(pkChanged)).isFalse();
        assertThat(FormulaDiff.needsFiling(FormulaDiff.diff(List.of(rm), List.of(new FormulaDiff.Line(1L, rm.qtyPer(), "OEM", true))))).isFalse();
        // 原料增、删、改量都要备案
        assertThat(FormulaDiff.needsFiling(FormulaDiff.diff(List.of(rm), List.of(new FormulaDiff.Line(1L, new BigDecimal("0.002"), "BRAND", true))))).isTrue();
        assertThat(FormulaDiff.needsFiling(FormulaDiff.diff(List.of(rm, pk), List.of(pk)))).isTrue();
        assertThat(FormulaDiff.needsFiling(FormulaDiff.diff(List.of(pk), List.of(rm, pk)))).isTrue();
    }

    @Test
    @Transactional
    @WithMockUser(username = "admin", roles = "ADMIN")
    void effectiveFormulaCanOnlyChangeThroughSignedVersion() {
        Item cream = item("FG-CRM-002");
        assertThatThrownBy(() -> master.saveBom(cream.getId(), new BomReq(linesFrom(cream.getId(), "RM-SQU-005", "0.006"))))
                .hasMessageContaining("配方与备案");

        var draft = formulas.createDraft(cream.getId(), new FormulaService.DraftReq("角鲨烷提到 12%", linesFrom(cream.getId(), "RM-SQU-005", "0.006")));
        assertThat(draft.needsFiling()).isTrue();
        assertThat(draft.changes()).hasSize(1);
        assertThatThrownBy(() -> formulas.createDraft(cream.getId(), new FormulaService.DraftReq("再改一次", linesFrom(cream.getId(), "RM-SQU-005", "0.007"))))
                .hasMessageContaining("进行中");
        assertThatThrownBy(() -> formulas.approve(draft.id(), user("qso1"))).hasMessageContaining("不能签批");

        formulas.submit(draft.id());
        // 发起人不能批自己的；原料变了没备案不能生效
        assertThatThrownBy(() -> formulas.approve(draft.id(), user("admin"))).hasMessageContaining("发起人");
        assertThatThrownBy(() -> formulas.approve(draft.id(), user("qso1"))).hasMessageContaining("备案");

        var filing = filings.create(new FilingService.FilingReq(cream.getId(), FilingType.GENERAL, draft.id(), time.today(), null));
        assertThatThrownBy(() -> formulas.approve(draft.id(), user("qso1"))).hasMessageContaining("备案");
        filings.complete(filing.id(), new FilingService.CompleteReq("新备案-001", time.today(), null));

        var effective = formulas.approve(draft.id(), user("qso1"));
        assertThat(effective.status()).isEqualTo(FormulaStatus.EFFECTIVE);
        assertThat(effective.filingNo()).isEqualTo("新备案-001");
        assertThat(item("FG-CRM-002").getFilingNo()).isEqualTo("新备案-001");
        assertThat(master.bomLines(cream.getId()).stream().filter(l -> l.getItemId().equals(item("RM-SQU-005").getId())).findFirst().orElseThrow().getQtyPer())
                .isEqualByComparingTo("0.006");
        // 只保留一个生效版本
        assertThat(versions.findByFgItemIdOrderByVersionNoDesc(cream.getId()).stream().filter(v -> v.getStatus() == FormulaStatus.EFFECTIVE)).hasSize(1);
        // 未发料的自产面霜单改用新版本
        MfgOrder planned = orders.findByStatusIn(List.of(MoStatus.PLANNED)).stream().filter(m -> m.getFgItemId().equals(cream.getId())).findFirst().orElseThrow();
        assertThat(planned.getFormulaVersionId()).isEqualTo(draft.id());
    }

    @Test
    @Transactional
    @WithMockUser(username = "admin", roles = "ADMIN")
    void issuedOrderKeepsTheFormulaVersionItWasIssuedWith() {
        Item serum = item("FG-SER-001");
        MfgOrder issued = orders.findByStatusIn(List.of(MoStatus.ISSUED)).stream().filter(m -> m.getFgItemId().equals(serum.getId())).findFirst().orElseThrow();
        Long oldVersion = issued.getFormulaVersionId();
        var before = production.detail(issued.getId()).requirements().stream().filter(r -> "RM-HA-003".equals(r.itemCode())).findFirst().orElseThrow().requiredQty();

        // 示例里 V2 已提交且备案在途：完成备案后签批生效
        var v2 = versions.findFirstByFgItemIdAndStatus(serum.getId(), FormulaStatus.PENDING).orElseThrow();
        var f = filings.list().stream().filter(x -> v2.getId().equals(x.formulaVersionId())).findFirst().orElseThrow();
        filings.complete(f.id(), new FilingService.CompleteReq("新备案-002", time.today(), null));
        formulas.approve(v2.getId(), user("qso1"));

        MfgOrder same = orders.findById(issued.getId()).orElseThrow();
        assertThat(same.getFormulaVersionId()).isEqualTo(oldVersion);
        assertThat(production.detail(issued.getId()).requirements().stream().filter(r -> "RM-HA-003".equals(r.itemCode())).findFirst().orElseThrow().requiredQty())
                .isEqualByComparingTo(before);
        assertThat(production.detail(issued.getId()).formulaVersionNo()).isEqualTo(1);
    }

    @Test
    @Transactional
    @WithMockUser(username = "qso1", roles = "QSO")
    void expiredRegistrationBlocksMarketRelease() {
        Batch pending = batches.findByStatusInOrderByIdDesc(List.of(BatchStatus.PENDING_RELEASE)).get(0);
        var f = filings.create(new FilingService.FilingReq(pending.getItemId(), FilingType.SPECIAL, null, time.today().minusYears(6), null));
        assertThatThrownBy(() -> filings.complete(f.id(), new FilingService.CompleteReq("注册证-1", time.today().minusYears(5), null))).hasMessageContaining("有效期");
        filings.complete(f.id(), new FilingService.CompleteReq("注册证-1", time.today().minusYears(5), time.today().minusDays(1)));
        assertThatThrownBy(() -> quality.marketRelease(pending.getId(), releaseReq(), user("qso1"))).hasMessageContaining("到期");
    }

    @Test
    @Transactional
    @WithMockUser(username = "admin", roles = "ADMIN")
    void filingNumberOnlyChangesThroughTheRegisterOnceRecorded() {
        Item fg = item("FG-SER-001");
        var same = new ItemReq(fg.getCode(), fg.getName() + "（改名）", ItemType.FG, fg.getUnit(), null, null, false, fg.getShelfMonths(),
                fg.getFilingNo(), fg.getNetG(), true, null, null);
        // 其它字段照常能改；编号不变不受影响
        assertThat(master.updateItem(fg.getId(), same).getName()).endsWith("（改名）");
        var changed = new ItemReq(fg.getCode(), fg.getName(), ItemType.FG, fg.getUnit(), null, null, false, fg.getShelfMonths(),
                "改过的编号", fg.getNetG(), true, null, null);
        assertThatThrownBy(() -> master.updateItem(fg.getId(), changed)).hasMessageContaining("备案登记");
        var cleared = new ItemReq(fg.getCode(), fg.getName(), ItemType.FG, fg.getUnit(), null, null, false, fg.getShelfMonths(),
                null, fg.getNetG(), true, null, null);
        assertThatThrownBy(() -> master.updateItem(fg.getId(), cleared)).hasMessageContaining("备案登记");

        // 初始化：新成品填已有编号时同时生成已完成的记录；之后同样不能直接改
        Item fresh = master.createItem(new ItemReq("FG-NEW-T", "新品", ItemType.FG, "瓶", null, null, false, 36, "历史编号-1",
                new BigDecimal("30"), true, null, null));
        assertThat(filings.list().stream().filter(f -> f.fgItemId().equals(fresh.getId())).findFirst().orElseThrow().filingNo()).isEqualTo("历史编号-1");
        // 没有编号的成品后来补填，也会补记录
        Item blank = master.createItem(new ItemReq("FG-NEW-U", "新品二", ItemType.FG, "瓶", null, null, false, 36, null,
                new BigDecimal("30"), true, null, null));
        assertThat(filings.list().stream().noneMatch(f -> f.fgItemId().equals(blank.getId()))).isTrue();
        master.updateItem(blank.getId(), new ItemReq("FG-NEW-U", "新品二", ItemType.FG, "瓶", null, null, false, 36, "历史编号-2",
                new BigDecimal("30"), true, null, null));
        assertThat(filings.list().stream().filter(f -> f.fgItemId().equals(blank.getId())).count()).isEqualTo(1);
    }

    private static QualityService.ReleaseReq releaseReq() {
        return new QualityService.ReleaseReq(Decision.RELEASED, "QC-T", "放行", true, true, true, true, 6, "留样室", DEMO);
    }

    // ---------- QMS ----------

    @Test
    @Transactional
    @WithMockUser(username = "qso1", roles = "QSO")
    void openMajorDeviationBlocksReleaseUntilCapaIsVerified() {
        Batch pending = batches.findByStatusInOrderByIdDesc(List.of(BatchStatus.PENDING_RELEASE)).get(0);
        var d = deviations.create(new DeviationService.DeviationReq(time.today(), DeviationArea.PRODUCTION, DeviationLevel.MAJOR, pending.getBatchNo(),
                "灌装量偏低", "抽检发现装量偏低", "隔离并复检", time.today().plusDays(10)));
        AppUser qso = user("qso1");
        assertThatThrownBy(() -> quality.marketRelease(pending.getId(), releaseReq(), qso)).hasMessageContaining(d.deviationNo());

        var closeReq = new DeviationService.CloseReq("复检合格，同意放行", DEMO);
        assertThatThrownBy(() -> deviations.close(d.id(), closeReq, qso)).hasMessageContaining("根本原因");
        deviations.investigate(d.id(), new DeviationService.InvestigateReq("灌装头磨损"));
        assertThatThrownBy(() -> deviations.close(d.id(), closeReq, qso)).hasMessageContaining("CAPA");

        var capa = deviations.createCapa(new DeviationService.CapaReq(CapaSource.DEVIATION, null, d.id(), CapaType.CORRECTIVE, "更换灌装头", "设备主管", time.today().plusDays(5)));
        assertThatThrownBy(() -> deviations.close(d.id(), closeReq, qso)).hasMessageContaining(capa.capaNo());
        // 没完成不能验证；完成人不能验证自己的；验证无效退回
        assertThatThrownBy(() -> deviations.verifyCapa(capa.id(), new DeviationService.CapaVerifyReq(true, "有效", DEMO), user("qa1"))).hasMessageContaining("不能验证");
        deviations.finishCapa(capa.id(), new DeviationService.CapaDoneReq("已更换"));
        assertThatThrownBy(() -> deviations.verifyCapa(capa.id(), new DeviationService.CapaVerifyReq(true, "有效", DEMO), qso)).hasMessageContaining("完成人以外");
        assertThat(deviations.verifyCapa(capa.id(), new DeviationService.CapaVerifyReq(false, "仍偏低", DEMO), user("qa1")).status()).isEqualTo(CapaStatus.OPEN);
        deviations.finishCapa(capa.id(), new DeviationService.CapaDoneReq("再次校准"));
        assertThat(deviations.verifyCapa(capa.id(), new DeviationService.CapaVerifyReq(true, "连续三批合格", DEMO), user("qa1")).status()).isEqualTo(CapaStatus.VERIFIED);

        assertThat(deviations.close(d.id(), closeReq, qso).status()).isEqualTo(CaseStatus.CLOSED);
        assertThat(quality.marketRelease(pending.getId(), releaseReq(), qso).status()).isEqualTo(BatchStatus.RELEASED);
        // 放行时留样自动入账
        assertThat(retainSamples.findAll().stream().filter(s -> s.getBatchId().equals(pending.getId()))).hasSize(1);
    }

    @Test
    @Transactional
    @WithMockUser(username = "qa1", roles = "QA")
    void criticalDeviationOnlyClosedByQso() {
        var d = deviations.create(new DeviationService.DeviationReq(time.today(), DeviationArea.QC, DeviationLevel.CRITICAL, null, "严重", "描述", null, time.today().plusDays(3)));
        deviations.investigate(d.id(), new DeviationService.InvestigateReq("原因"));
        assertThatThrownBy(() -> deviations.close(d.id(), new DeviationService.CloseReq("结论", DEMO), user("purchase1"))).hasMessageContaining("质量安全负责人");
    }

    @Test
    @Transactional
    @WithMockUser(username = "qa1", roles = "QA")
    void failedSupplierAuditDisqualifiesAndPassRestores() {
        Partner sup = partners.findAll().stream().filter(p -> p.getKind() == PartnerKind.SUPPLIER && p.isQualified()).findFirst().orElseThrow();
        AppUser qa = user("qa1");
        var failed = new SupplierAuditService.AuditReq(sup.getId(), time.today(), AuditType.FOR_CAUSE, 40, AuditResult.FAIL, "关键记录造假", "张工", null, DEMO);
        assertThatThrownBy(() -> audits.create(new SupplierAuditService.AuditReq(sup.getId(), time.today(), AuditType.ROUTINE, 90, AuditResult.PASS, null, "张工", null, DEMO), qa))
                .hasMessageContaining("下次审核");
        audits.create(failed, qa);
        assertThat(partners.findById(sup.getId()).orElseThrow().isQualified()).isFalse();
        audits.create(new SupplierAuditService.AuditReq(sup.getId(), time.today(), AuditType.FOR_CAUSE, 90, AuditResult.PASS, null, "张工", time.today().plusDays(10), DEMO), qa);
        assertThat(partners.findById(sup.getId()).orElseThrow().isQualified()).isTrue();
        assertThat(audits.partnerRows().stream().filter(r -> r.partnerId().equals(sup.getId())).findFirst().orElseThrow().dueState()).isEqualTo("DUE");
    }

    @Test
    @Transactional
    @WithMockUser(username = "qa1", roles = "QA")
    void retainSampleCannotBeDestroyedBeforeItsDate() {
        var sample = retains.list().stream().filter(s -> s.status() == RetainStatus.HELD).findFirst().orElseThrow();
        assertThatThrownBy(() -> retains.destroy(sample.id(), new RetainService.DestroyReq("焚烧", DEMO), user("qa1"))).hasMessageContaining("保存期满");
        assertThatThrownBy(() -> retains.use(sample.id(), new RetainService.UseReq(sample.remainingQty() + 1, "复检"))).hasMessageContaining("只剩");
        var used = retains.use(sample.id(), new RetainService.UseReq(2, "投诉复检"));
        assertThat(used.remainingQty()).isEqualTo(sample.remainingQty() - 2);
        assertThat(used.txns()).hasSize(1);

        RetainSample s = retainSamples.findById(sample.id()).orElseThrow();
        s.setRetainUntil(time.today().minusDays(1));
        retainSamples.save(s);
        assertThat(retains.dueForDestruction()).extracting(RetainService.SampleView::id).contains(sample.id());
        assertThat(retains.destroy(sample.id(), new RetainService.DestroyReq("焚烧，质量部监督", DEMO), user("qa1")).status()).isEqualTo(RetainStatus.DESTROYED);
    }

    // ---------- MES ----------

    private static final MesService.ClearanceReq BEFORE = new MesService.ClearanceReq(ClearancePhase.BEFORE, "2 号车间", "上批面霜", true, true, true, null, DEMO);
    private static final MesService.ClearanceReq AFTER = new MesService.ClearanceReq(ClearancePhase.AFTER, "2 号车间", null, true, true, true, null, DEMO);

    private MfgOrder issuedOwnOrder() {
        MfgOrder mo = orders.findByStatusIn(List.of(MoStatus.PLANNED)).stream().filter(m -> m.getMoType() == MoType.OWN).findFirst().orElseThrow();
        production.issue(mo.getId());
        return mo;
    }

    private ProductionService.CompleteReq completeReq(Long fgWarehouse) {
        return new ProductionService.CompleteReq(new BigDecimal("8000"), time.today(), null, fgWarehouse, false, null, List.of());
    }

    private Long ownWarehouse() {
        return batches.findAll().isEmpty() ? null : master.listWarehouses().stream()
                .filter(w -> "WH-MAIN".equals(w.code())).findFirst().orElseThrow().id();
    }

    @Test
    @Transactional
    @WithMockUser(username = "prod1", roles = "PRODUCTION")
    void batchRecordEnforcesOrderReviewAndClearanceBeforeCompletion() {
        MfgOrder mo = issuedOwnOrder();
        var record = mes.record(mo.getId());
        assertThat(record.hasRecord()).isTrue();
        assertThat(record.steps()).hasSize(5);
        Long wh = ownWarehouse();
        assertThatThrownBy(() -> production.complete(mo.getId(), completeReq(wh))).hasMessageContaining("批生产记录未完成");

        var ok = new MesService.StepReq(null, null);
        assertThatThrownBy(() -> mes.recordStep(mo.getId(), 1, ok)).hasMessageContaining("开工前清场");
        mes.clearance(mo.getId(), BEFORE, user("prod1"));
        assertThatThrownBy(() -> mes.clearance(mo.getId(), BEFORE, user("prod1"))).hasMessageContaining("已确认");
        assertThatThrownBy(() -> mes.clearance(mo.getId(), AFTER, user("prod1"))).hasMessageContaining("工序全部执行");
        assertThatThrownBy(() -> mes.recordStep(mo.getId(), 2, new MesService.StepReq(new BigDecimal("75"), null))).hasMessageContaining("按顺序");

        mes.recordStep(mo.getId(), 1, ok);
        assertThatThrownBy(() -> mes.recordStep(mo.getId(), 1, ok)).hasMessageContaining("不能修改");
        // 关键工序未复核不能往下走；操作人不能复核自己
        assertThatThrownBy(() -> mes.recordStep(mo.getId(), 2, new MesService.StepReq(new BigDecimal("75"), null))).hasMessageContaining("复核");
        assertThatThrownBy(() -> mes.review(mo.getId(), 1, user("prod1"))).hasMessageContaining("操作人以外");
        assertThat(mes.review(mo.getId(), 1, user("qa1")).steps().get(0).status()).isEqualTo(StepStatus.REVIEWED);
        assertThatThrownBy(() -> mes.recordStep(mo.getId(), 2, ok)).hasMessageContaining("选择使用的设备");
        assertThatThrownBy(() -> mes.recordStep(mo.getId(), 2, new MesService.StepReq(null, null, equipmentId("EQ-EM-01")))).hasMessageContaining("实测值");
        mes.recordStep(mo.getId(), 2, new MesService.StepReq(new BigDecimal("75"), null, equipmentId("EQ-EM-01")));
        mes.recordStep(mo.getId(), 3, new MesService.StepReq(new BigDecimal("6.2"), null));
        mes.recordStep(mo.getId(), 4, new MesService.StepReq(new BigDecimal("50.5"), null, equipmentId("EQ-FL-01")));
        mes.review(mo.getId(), 4, user("qa1"));
        mes.recordStep(mo.getId(), 5, ok);
        assertThat(mes.record(mo.getId()).missing()).containsExactly("完工后清场未确认");
        mes.clearance(mo.getId(), AFTER, user("qa1"));
        assertThat(mes.record(mo.getId()).missing()).isEmpty();
        assertThat(production.complete(mo.getId(), completeReq(wh)).status()).isEqualTo(MoStatus.COMPLETED);
    }

    @Test
    @Transactional
    @WithMockUser(username = "prod1", roles = "PRODUCTION")
    void outOfSpecIpcCreatesDeviationThatBlocksRelease() {
        MfgOrder mo = issuedOwnOrder();
        mes.clearance(mo.getId(), BEFORE, user("prod1"));
        mes.recordStep(mo.getId(), 1, new MesService.StepReq(null, null));
        mes.review(mo.getId(), 1, user("qa1"));
        // 超限必须写明现场情况
        assertThatThrownBy(() -> mes.recordStep(mo.getId(), 2, new MesService.StepReq(new BigDecimal("85"), null, equipmentId("EQ-EM-01")))).hasMessageContaining("超出限度");
        var r = mes.recordStep(mo.getId(), 2, new MesService.StepReq(new BigDecimal("85"), "温控失灵，已停机调整", equipmentId("EQ-EM-01")));
        var step = r.steps().get(1);
        assertThat(step.ipcPass()).isFalse();
        assertThat(step.deviationNo()).startsWith("DV-");
        mes.recordStep(mo.getId(), 3, new MesService.StepReq(new BigDecimal("6"), null));
        mes.recordStep(mo.getId(), 4, new MesService.StepReq(new BigDecimal("50"), null, equipmentId("EQ-FL-01")));
        mes.review(mo.getId(), 4, user("qa1"));
        mes.recordStep(mo.getId(), 5, new MesService.StepReq(null, null));
        mes.clearance(mo.getId(), AFTER, user("qa1"));
        var done = production.complete(mo.getId(), completeReq(ownWarehouse()));

        // 入库后偏差自动挂到成品批次上，没关闭不能上市放行
        Batch out = batches.findAll().stream().filter(b -> b.getBatchNo().equals(done.outBatchNo())).findFirst().orElseThrow();
        assertThat(deviations.list().stream().filter(d -> d.deviationNo().equals(step.deviationNo())).findFirst().orElseThrow().batchNo()).isEqualTo(done.outBatchNo());
        assertThatThrownBy(() -> quality.marketRelease(out.getId(), releaseReq(), user("qso1"))).hasMessageContaining(step.deviationNo());
    }

    @Test
    @Transactional
    @WithMockUser(username = "prod1", roles = "PRODUCTION")
    void orderWithoutRoutingOrOemHasNoRecordGate() {
        MfgOrder oem = orders.findByStatusIn(List.of(MoStatus.ISSUED)).stream().filter(m -> m.getMoType() == MoType.OEM).findFirst().orElseThrow();
        assertThat(mes.record(oem.getId()).hasRecord()).isFalse();
        assertThat(mes.missing(oem.getId())).isEmpty();
        assertThatThrownBy(() -> mes.recordStep(oem.getId(), 1, new MesService.StepReq(null, null))).hasMessageContaining("没有批生产记录");
    }

    @Test
    void writingRecordsIsLimitedByRole() throws Exception {
        // 只读账号不能改配方、不能登记偏差；门户账号碰不到内部接口
        mvc.perform(post("/api/plm/versions/1/approve").contentType("application/json").content("{\"password\":\"x\"}")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/qms/deviations").contentType("application/json")
                .content("{\"occurredDate\":\"2026-01-01\",\"area\":\"QC\",\"level\":\"MINOR\",\"title\":\"t\",\"description\":\"d\",\"dueDate\":\"2026-02-01\"}")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("viewer1").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/reports/stock")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("dealer1").roles("DEALER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/notify/status")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("qa1").roles("QA"))).andExpect(status().isForbidden());
    }

    // ---------- 报表 ----------

    private static Map<String, String> unzip(byte[] xlsx) throws Exception {
        Map<String, String> out = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(xlsx), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                out.put(e.getName(), new String(z.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    @Test
    void xlsxEscapesContentAndNeverWritesFormulas() throws Exception {
        byte[] x = XlsxWriter.write("报表/测试", List.of("文本", "数字"), List.of(
                java.util.Arrays.asList("=1+1 <a&b>\u0001", new BigDecimal("12.50")),
                java.util.Arrays.asList(null, 3)));
        Map<String, String> f = unzip(x);
        assertThat(f).containsKeys("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/styles.xml");
        String sheet = f.get("xl/worksheets/sheet1.xml");
        assertThat(sheet).contains("=1+1 &lt;a&amp;b&gt;").doesNotContain("<f>").doesNotContain("\u0001");
        assertThat(sheet).contains("<v>12.50</v>").contains("<v>3</v>");
        assertThat(f.get("xl/workbook.xml")).contains("name=\"报表 测试\"");
        // 每个 XML 都能被解析
        var dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        for (String content : f.values()) {
            dbf.newDocumentBuilder().parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    @Transactional
    @WithMockUser(username = "qa1", roles = "QA")
    void everyReportExportsAndIsAudited() throws Exception {
        for (String key : List.of("stock", "batches", "production", "shipments", "complaints", "deviations", "capas", "supplier-audits", "retains", "audit")) {
            ReportService.Export e = reports.export(key, null, null);
            assertThat(e.fileName()).endsWith(".xlsx");
            assertThat(unzip(e.content()).get("xl/worksheets/sheet1.xml")).contains("<row r=\"1\">");
        }
        assertThat(unzip(reports.export("stock", null, null).content()).get("xl/worksheets/sheet1.xml")).contains("烟酰胺精华液");
        assertThat(unzip(reports.export("audit", null, null).content()).get("xl/worksheets/sheet1.xml")).contains("导出报表");
        assertThatThrownBy(() -> reports.export("nope", null, null)).hasMessageContaining("不存在");
    }

    @Test
    @Transactional
    @WithMockUser(username = "qa1", roles = "QA")
    void exportsContainAllRowsNotJustTheLatestPage() throws Exception {
        // 页面列表只取最近 300 张；导出必须是全部
        Item fg = item("FG-SER-001");
        Partner factory = partners.findAll().stream().filter(p -> p.getKind() == PartnerKind.OEM).findFirst().orElseThrow();
        for (int i = 0; i < 305; i++) {
            MfgOrder m = new MfgOrder();
            m.setMoNo("MO-BULK-" + i);
            m.setMoType(MoType.OEM);
            m.setFactoryId(factory.getId());
            m.setFgItemId(fg.getId());
            m.setPlannedQty(BigDecimal.TEN);
            m.setStatus(MoStatus.PLANNED);
            m.setCreatedAt(time.now());
            orders.save(m);
        }
        assertThat(production.list()).hasSize(300);
        String sheet = unzip(reports.export("production", null, null).content()).get("xl/worksheets/sheet1.xml");
        assertThat(sheet).contains("MO-BULK-0<").contains("MO-BULK-304<");
    }

    @Test
    @Transactional
    @WithMockUser(username = "qa1", roles = "QA")
    void auditExportHonoursDateRange() throws Exception {
        LocalDate today = time.today();
        String inRange = unzip(reports.export("audit", today, today).content()).get("xl/worksheets/sheet1.xml");
        assertThat(inRange).contains("<row r=\"2\">");
        String past = unzip(reports.export("audit", today.minusYears(5), today.minusYears(4)).content()).get("xl/worksheets/sheet1.xml");
        assertThat(past).doesNotContain("<row r=\"2\">");
    }

    // ---------- 取消 ----------

    @Test
    @Transactional
    @WithMockUser(username = "prod1", roles = "PRODUCTION")
    void plannedOrderCancelsAndDropsOutOfWorkInProgress() {
        MfgOrder mo = orders.findByStatusIn(List.of(MoStatus.PLANNED)).stream().filter(m -> m.getMoType() == MoType.OWN).findFirst().orElseThrow();
        var view = production.cancel(mo.getId(), new ProductionService.CancelReq("计划调整"));
        assertThat(view.status()).isEqualTo(MoStatus.CANCELLED);
        assertThat(view.cancelReason()).isEqualTo("计划调整");
        assertThat(orders.findByStatusIn(List.of(MoStatus.PLANNED, MoStatus.ISSUED))).noneMatch(m -> m.getId().equals(mo.getId()));
        assertThatThrownBy(() -> production.issue(mo.getId())).hasMessageContaining("不能发料");
        assertThatThrownBy(() -> production.cancel(mo.getId(), new ProductionService.CancelReq("再取消"))).hasMessageContaining("不能取消");
    }

    @Test
    @Transactional
    @WithMockUser(username = "prod1", roles = "PRODUCTION")
    void cancellingIssuedOrderReturnsMaterialsAndClearsTrace() {
        MfgOrder mo = issuedOwnOrder();
        var issuedRows = issues.findByMoId(mo.getId());
        assertThat(issuedRows).isNotEmpty();
        Long batchId = issuedRows.get(0).getBatchId();
        BigDecimal qty = issuedRows.get(0).getQty();
        BigDecimal before = inventory.onHand(batchId);

        production.cancel(mo.getId(), new ProductionService.CancelReq("开错单"));
        assertThat(inventory.onHand(batchId)).isEqualByComparingTo(before.add(qty));
        assertThat(issues.findByMoId(mo.getId())).isEmpty();
        assertThat(issues.findByBatchId(batchId)).noneMatch(i -> i.getMoId().equals(mo.getId()));
    }

    @Test
    @Transactional
    @WithMockUser(username = "prod1", roles = "PRODUCTION")
    void startedOrderCannotBeCancelled() {
        MfgOrder mo = issuedOwnOrder();
        mes.clearance(mo.getId(), BEFORE, user("prod1"));
        assertThatThrownBy(() -> production.cancel(mo.getId(), new ProductionService.CancelReq("不想做了"))).hasMessageContaining("不能取消");
        // 受托厂已回传生产中的，也不能取消
        MfgOrder oem = orders.findByStatusIn(List.of(MoStatus.ISSUED)).stream().filter(m -> m.getMoType() == MoType.OEM).findFirst().orElseThrow();
        OemProgress p = new OemProgress();
        p.setMoId(oem.getId());
        p.setStage(OemStage.PRODUCING);
        p.setCreatedBy("oem1");
        p.setCreatedAt(time.now());
        progress.save(p);
        assertThatThrownBy(() -> production.cancel(oem.getId(), new ProductionService.CancelReq("不想做了"))).hasMessageContaining("生产中");
    }

    @Test
    @Transactional
    @WithMockUser(username = "purchase1", roles = "PURCHASE")
    void purchaseOrderCancelOrCloseStopsReceivingAndOnOrder() {
        Partner sup = partners.findAll().stream().filter(p -> p.getKind() == PartnerKind.SUPPLIER && p.isQualified()).findFirst().orElseThrow();
        Item rm = item("RM-GLY-002");
        var open = purchases.create(new PurchaseService.CreatePoReq(sup.getId(), List.of(new PurchaseService.PoLineReq(rm.getId(), BigDecimal.TEN))));
        var cancelled = purchases.close(open.id(), new PurchaseService.CloseReq("下错了"));
        assertThat(cancelled.status()).isEqualTo(PoStatus.CANCELLED);
        Long wh = ownWarehouse();
        var line = new PurchaseService.ReceiveLineReq(open.lines().get(0).id(), BigDecimal.ONE, "EXT-1", time.today(), null, wh);
        assertThatThrownBy(() -> purchases.receive(open.id(), new PurchaseService.ReceiveReq(List.of(line)))).hasMessageContaining("不能收货");
        assertThatThrownBy(() -> purchases.close(open.id(), new PurchaseService.CloseReq("再关"))).hasMessageContaining("不能取消");

        var part = purchases.create(new PurchaseService.CreatePoReq(sup.getId(), List.of(new PurchaseService.PoLineReq(rm.getId(), BigDecimal.TEN))));
        purchases.receive(part.id(), new PurchaseService.ReceiveReq(List.of(new PurchaseService.ReceiveLineReq(part.lines().get(0).id(), BigDecimal.ONE, "EXT-2", time.today(), null, wh))));
        assertThat(purchases.close(part.id(), new PurchaseService.CloseReq("供应商停产")).status()).isEqualTo(PoStatus.CLOSED);
    }

    // ---------- 预测接入 ----------

    @Test
    @Transactional
    @WithMockUser(username = "prod1", roles = "PRODUCTION")
    void planningCanUseSeasonalForecastInsteadOfHistoricalAverage() {
        Item cream = item("FG-CRM-002");
        Partner customer = partners.findAll().stream().filter(p -> p.getKind() == PartnerKind.CUSTOMER).findFirst().orElseThrow();
        Long wh = ownWarehouse();
        // 造 24 个月出库：每年“下个月”是旺季（1000），其它月 100
        java.time.YearMonth thisMonth = java.time.YearMonth.from(time.today());
        for (int back = 24; back >= 1; back--) {
            java.time.YearMonth m = thisMonth.minusMonths(back);
            SalesOrder so = new SalesOrder();
            so.setSoNo("SO-H-" + back);
            so.setCustomerId(customer.getId());
            so.setWarehouseId(wh);
            so.setOrderDate(m.atDay(15));
            so.setStatus("SHIPPED");
            so.setCreatedAt(time.now());
            salesOrders.save(so);
            SoLine l = new SoLine();
            l.setSoId(so.getId());
            l.setItemId(cream.getId());
            l.setQty(BigDecimal.valueOf(m.getMonthValue() == thisMonth.plusMonths(1).getMonthValue() ? 1000 : 100));
            soLines.save(l);
        }
        java.util.function.Function<Boolean, PlanningService.FgRow> row = use -> planning.finishedGoods(new PlanningService.PlanParams(90, 30, 15, 60, use))
                .stream().filter(r -> r.code().equals("FG-CRM-002")).findFirst().orElseThrow();
        PlanningService.FgRow history = row.apply(false);
        assertThat(history.forecastMethod()).isEqualTo("季节指数");
        assertThat(history.forecast()).hasSize(3);
        // 下个月的预测远高于本月
        assertThat(history.forecast().get(1).qty().compareTo(history.forecast().get(0).qty().multiply(BigDecimal.valueOf(3)))).isPositive();
        // 打开开关后日均来自预测，和历史平均不同
        PlanningService.FgRow fc = row.apply(true);
        assertThat(fc.dailyDemand()).isEqualByComparingTo(fc.forecastDaily());
        assertThat(fc.dailyDemand()).isNotEqualByComparingTo(history.dailyDemand());
    }

    // ---------- 设备 ----------

    private Long equipmentId(String code) {
        return equipmentRepo.findAll().stream().filter(e -> e.getCode().equals(code)).findFirst().orElseThrow().getId();
    }

    /** 走到需要设备的第 2 道工序前。 */
    private MfgOrder readyForEquipmentStep() {
        MfgOrder mo = issuedOwnOrder();
        mes.clearance(mo.getId(), BEFORE, user("prod1"));
        mes.recordStep(mo.getId(), 1, new MesService.StepReq(null, null));
        mes.review(mo.getId(), 1, user("qa1"));
        return mo;
    }

    @Test
    @Transactional
    @WithMockUser(username = "prod1", roles = "PRODUCTION")
    void expiredOrStoppedEquipmentCannotBeUsedInBatchRecord() {
        MfgOrder mo = readyForEquipmentStep();
        Long id = equipmentId("EQ-EM-01");
        var req = new MesService.StepReq(new BigDecimal("75"), null, id);

        Equipment e = equipmentRepo.findById(id).orElseThrow();
        e.setNextDue(time.today().minusDays(1));
        equipmentRepo.save(e);
        assertThatThrownBy(() -> mes.recordStep(mo.getId(), 2, req)).hasMessageContaining("校准已于");

        equipment.calibrate(id, new EquipmentService.CalibrationReq(time.today(), CalibrationResult.PASS, "CAL-1", null, null));
        // 没填下次日期时按周期算
        assertThat(equipmentRepo.findById(id).orElseThrow().getNextDue()).isEqualTo(time.today().plusMonths(12));
        equipment.stop(id, new EquipmentService.StopReq("维修"));
        assertThatThrownBy(() -> mes.recordStep(mo.getId(), 2, req)).hasMessageContaining("已停用");
        equipment.resume(id);

        var step = mes.recordStep(mo.getId(), 2, req).steps().get(1);
        assertThat(step.equipmentName()).contains("EQ-EM-01");
    }

    @Test
    @Transactional
    @WithMockUser(username = "prod1", roles = "PRODUCTION")
    void failedCalibrationStopsEquipmentAndRaisesDeviationListingOrders() {
        Long id = equipmentId("EQ-EM-01");
        assertThatThrownBy(() -> equipment.calibrate(id, new EquipmentService.CalibrationReq(time.today(), CalibrationResult.PASS, null, null, null))).hasMessageContaining("证书");
        assertThatThrownBy(() -> equipment.calibrate(id, new EquipmentService.CalibrationReq(time.today(), CalibrationResult.FAIL, null, null, null))).hasMessageContaining("写明情况");
        assertThatThrownBy(() -> equipment.calibrate(id, new EquipmentService.CalibrationReq(time.today().plusDays(1), CalibrationResult.PASS, "C", null, null))).hasMessageContaining("晚于今天");

        MfgOrder mo = readyForEquipmentStep();
        mes.recordStep(mo.getId(), 2, new MesService.StepReq(new BigDecimal("75"), null, id));

        var v = equipment.calibrate(id, new EquipmentService.CalibrationReq(time.today(), CalibrationResult.FAIL, null, null, "偏差超出允差"));
        assertThat(v.status()).isEqualTo(EquipmentStatus.STOPPED);
        assertThat(v.usable()).isFalse();
        var dv = deviations.open().stream().filter(d -> d.title().contains("EQ-EM-01")).findFirst().orElseThrow();
        assertThat(dv.level()).isEqualTo(DeviationLevel.MAJOR);
        assertThat(dv.description()).contains("偏差超出允差").contains(mo.getMoNo());
    }

    @Test
    @Transactional
    @WithMockUser(username = "qa1", roles = "QA")
    void equipmentDueSoonShowsInDashboardAndExports() throws Exception {
        assertThat(notify.digest().items()).extracting(DashboardTodoKind::of).contains("EQUIPMENT"); // 示例里电子秤 20 天后到期
        assertThat(unzip(reports.export("equipment", null, null).content()).get("xl/worksheets/sheet1.xml")).contains("EQ-SC-01");
    }

    // ---------- 期初库存 ----------

    private static final BatchStatus REL = BatchStatus.RELEASED;

    private OpeningStockService.OpeningRow row(String code, String batch, String wh, String qty, BatchStatus st, String report, Integer retain, String loc) {
        return new OpeningStockService.OpeningRow(code, batch, "EXT-" + batch, time.today().minusMonths(2), null, wh, new BigDecimal(qty), st, null, report, retain, loc);
    }

    @Test
    @Transactional
    @WithMockUser(username = "qso1", roles = "QSO")
    void openingStockCreatesBatchesWithDecisionsAndRetainSamples() {
        var rows = List.of(
                row("RM-GLY-002", "OPEN-RM-1", "WH-MAIN", "12.5", REL, "COA-OLD-1", null, null),
                row("RM-GLY-002", "OPEN-RM-2", "WH-MAIN", "3", BatchStatus.QUARANTINE, null, null, null),
                row("FG-SER-001", "OPEN-FG-1", "WH-MAIN", "100", REL, "QC-OLD-1", 6, "留样室 C-1"));
        // 预检不写库
        var dry = opening.run(new OpeningStockService.OpeningReq(rows, true, null), null);
        assertThat(dry.errors()).isEmpty();
        assertThat(dry.applied()).isFalse();
        assertThat(batches.findAll()).noneMatch(b -> b.getBatchNo().startsWith("OPEN-"));

        var done = opening.run(new OpeningStockService.OpeningReq(rows, false, DEMO), user("qso1"));
        assertThat(done.applied()).isTrue();
        assertThat(done.created()).isEqualTo(3);
        Batch rel = batches.findAll().stream().filter(b -> b.getBatchNo().equals("OPEN-RM-1")).findFirst().orElseThrow();
        assertThat(rel.getStatus()).isEqualTo(REL);
        assertThat(rel.getSourceType()).isEqualTo(SourceType.OPEN);
        assertThat(rel.getExpDate()).isNotNull(); // 没填有效期时按保质期算
        assertThat(inventory.onHand(rel.getId())).isEqualByComparingTo("12.5");
        Batch fg = batches.findAll().stream().filter(b -> b.getBatchNo().equals("OPEN-FG-1")).findFirst().orElseThrow();
        assertThat(quality.decisionsOf(fg.getId()).get(0).signedByName()).isEqualTo(user("qso1").getDisplayName());
        assertThat(retainSamples.findAll()).anyMatch(s -> s.getBatchId().equals(fg.getId()) && s.getQty() == 6);
        // 期初已放行的成品能被出库分配到，和正常批次一样
        assertThat(inventory.listStock(null, null, "OPEN-FG-1", false)).hasSize(1);
    }

    @Test
    @Transactional
    @WithMockUser(username = "qso1", roles = "QSO")
    void openingStockIsAllOrNothingAndReportsEveryBadRow() {
        var rows = List.of(
                row("RM-GLY-002", "OK-1", "WH-MAIN", "1", REL, "COA", null, null),
                row("NOPE", "BAD-1", "WH-MAIN", "1", REL, "COA", null, null),
                row("RM-GLY-002", "BAD-2", "WH-NOPE", "1", REL, "COA", null, null),
                row("RM-GLY-002", "BAD-3", "WH-MAIN", "1", REL, null, null, null),
                row("FG-SER-001", "BAD-4", "WH-MAIN", "1", REL, "QC", null, null),
                row("FG-SER-001", "BAD-5", "WH-MAIN", "1", BatchStatus.QUARANTINE, null, null, null),
                row("RM-GLY-002", "BAD-6", "WH-MAIN", "1", BatchStatus.PENDING_RELEASE, null, null, null),
                new OpeningStockService.OpeningRow("RM-GLY-002", "BAD-7", null, time.today().minusYears(5), time.today().minusDays(1), "WH-MAIN", BigDecimal.ONE, REL, null, "COA", null, null),
                row("RM-GLY-002", "BAD-8", "WH-RTN", "1", BatchStatus.QUARANTINE, null, null, null),
                new OpeningStockService.OpeningRow("RM-GLY-002", null, null, null, null, "WH-MAIN", BigDecimal.ZERO, REL, null, "COA", null, null));
        var r = opening.run(new OpeningStockService.OpeningReq(rows, false, DEMO), user("qso1"));
        assertThat(r.applied()).isFalse();
        assertThat(r.errors()).hasSize(9);
        assertThat(r.errors().get(0).line()).isEqualTo(3); // 按 Excel 行号
        assertThat(batches.findAll()).noneMatch(b -> b.getBatchNo().equals("OK-1"));
        assertThat(r.errors()).anyMatch(x -> x.message().contains("退货隔离区"));

        // 批号已存在
        String existing = batches.findAll().get(0).getBatchNo();
        var dup = opening.run(new OpeningStockService.OpeningReq(List.of(row("RM-GLY-002", existing, "WH-MAIN", "1", BatchStatus.QUARANTINE, null, null, null)), true, null), null);
        assertThat(dup.errors().get(0).message()).contains("已存在");
    }

    @Test
    @Transactional
    @WithMockUser(username = "qso1", roles = "QSO")
    void sameBatchAcrossWarehousesMergesAndRejectsConflicts() {
        Long main = ownWarehouse();
        String other = master.listWarehouses().stream().filter(w -> !w.id().equals(main) && !"RETURN".equals(w.kind().name())).findFirst().orElseThrow().code();
        var ok = opening.run(new OpeningStockService.OpeningReq(List.of(
                row("RM-GLY-002", "MULTI-1", "WH-MAIN", "5", BatchStatus.QUARANTINE, null, null, null),
                row("RM-GLY-002", "MULTI-1", other, "7", BatchStatus.QUARANTINE, null, null, null)), false, DEMO), user("qso1"));
        assertThat(ok.applied()).isTrue();
        assertThat(ok.created()).isEqualTo(1);
        Batch b = batches.findAll().stream().filter(x -> x.getBatchNo().equals("MULTI-1")).findFirst().orElseThrow();
        assertThat(b.getQtyIn()).isEqualByComparingTo("12");
        assertThat(inventory.onHand(b.getId())).isEqualByComparingTo("12");

        var conflict = opening.run(new OpeningStockService.OpeningReq(List.of(
                row("RM-GLY-002", "MULTI-2", "WH-MAIN", "5", BatchStatus.QUARANTINE, null, null, null),
                row("RM-SQU-005", "MULTI-2", other, "7", BatchStatus.QUARANTINE, null, null, null),
                row("RM-GLY-002", "MULTI-3", "WH-MAIN", "5", BatchStatus.QUARANTINE, null, null, null),
                row("RM-GLY-002", "MULTI-3", "WH-MAIN", "1", BatchStatus.QUARANTINE, null, null, null)), true, null), null);
        assertThat(conflict.errors()).hasSize(2);
    }

    @Test
    void openingStockReportsMissingFieldsPerRowThroughTheApi() throws Exception {
        var qso = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("qso1").roles("QSO");
        // 缺生产日期、数量为空：要按行报错，不是整个请求 400
        mvc.perform(post("/api/opening-stock").contentType("application/json").with(qso)
                        .content("{\"rows\":[{\"itemCode\":\"RM-GLY-002\",\"batchNo\":\"API-1\",\"warehouseCode\":\"WH-MAIN\",\"status\":\"QUARANTINE\"}],\"dryRun\":true}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.errors[0].line").value(2))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.errors[0].code").value("API-1"));
        // 真正导入不带密码：明确提示，不是系统错误
        mvc.perform(post("/api/opening-stock").contentType("application/json").with(qso)
                        .content("{\"rows\":[{\"itemCode\":\"RM-GLY-002\",\"batchNo\":\"API-2\",\"mfgDate\":\"2026-01-01\",\"warehouseCode\":\"WH-MAIN\",\"qty\":1,\"status\":\"QUARANTINE\"}],\"dryRun\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value("请输入签批密码"));
    }

    @Test
    void wrongUrlsAreClientErrorsNotServerErrors() throws Exception {
        var qa = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("qa1").roles("QA");
        mvc.perform(get("/api/no-such-endpoint").with(qa)).andExpect(status().isNotFound());
        mvc.perform(post("/api/qms/retains").with(qa)).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/reports/stock").param("from", "不是日期").with(qa)).andExpect(status().isBadRequest());
    }

    @Test
    void openingStockOnlyForQso() throws Exception {
        var as = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN");
        mvc.perform(post("/api/opening-stock").contentType("application/json")
                .content("{\"rows\":[{\"itemCode\":\"X\",\"batchNo\":\"Y\",\"mfgDate\":\"2026-01-01\",\"warehouseCode\":\"W\",\"qty\":1,\"status\":\"RELEASED\"}],\"dryRun\":true}")
                .with(as)).andExpect(status().isForbidden());
    }

    // ---------- 数据量大时的正确性 ----------

    @Test
    @Transactional
    @WithMockUser(username = "qso1", roles = "QSO")
    void nearExpiryAlertFindsOldBatchesEvenWithManyNewerOnes() {
        // 最老的批次最可能临期：不能只看最近几百个批次
        LocalDate mfg = time.today().minusMonths(32);
        opening.run(new OpeningStockService.OpeningReq(List.of(new OpeningStockService.OpeningRow("FG-SER-001", "OLD-NEAR-EXP", null, mfg, null, "WH-MAIN",
                BigDecimal.TEN, REL, null, "QC", 6, "留样室")), false, DEMO), user("qso1"));
        Item rm = item("RM-GLY-002");
        for (int i = 0; i < 520; i++) {
            Batch b = new Batch();
            b.setBatchNo("NEWER-" + i);
            b.setItemId(rm.getId());
            b.setMfgDate(time.today());
            b.setExpDate(time.today().plusYears(2));
            b.setStatus(BatchStatus.QUARANTINE);
            b.setSourceType(SourceType.OPEN);
            b.setSourceNo("T");
            b.setQtyIn(BigDecimal.ONE);
            b.setCreatedAt(time.now());
            batches.save(b);
        }
        assertThat(dashboard.summary().todos()).anyMatch(t -> "NEAR_EXPIRY".equals(t.kind()) && "OLD-NEAR-EXP".equals(t.ref()));
    }

    @Test
    void shortCacheComputesOnceAndExpires() throws Exception {
        var cache = new com.cosmetic.scm.common.ShortCache(30);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
        for (int i = 0; i < 8; i++) {
            futures.add(pool.submit(() -> cache.get("k", () -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return calls.incrementAndGet();
            })));
        }
        for (var f : futures) {
            assertThat(f.get()).isEqualTo(1);
        }
        pool.shutdown();
        assertThat(calls.get()).isEqualTo(1);
        // 丢弃后重新计算
        cache.evictPrefix("k");
        assertThat(cache.get("k", calls::incrementAndGet)).isEqualTo(2);
        calls.set(1);
        // ttl 为 0 时不缓存
        var off = new com.cosmetic.scm.common.ShortCache(0);
        off.get("k", calls::incrementAndGet);
        off.get("k", calls::incrementAndGet);
        assertThat(calls.get()).isEqualTo(3);
    }

    // ---------- 提醒 ----------

    @Test
    @Transactional
    @WithMockUser(username = "admin", roles = "ADMIN")
    void digestSendsMailOnlyWarningsAndRecordsFailures() {
        NotifyService.Digest d = notify.digest();
        assertThat(d.items()).isNotEmpty();
        assertThat(d.items()).allMatch(t -> "bad".equals(t.level()) || "warn".equals(t.level()));
        // 新增的 QMS / PLM 待办进了摘要：配方待签批、明瓶审核快到期
        assertThat(d.items()).extracting(DashboardTodoKind::of).contains("FORMULA", "SUPPLIER_AUDIT");

        NotifyService.SendResult ok = notify.sendNow();
        assertThat(ok.sent()).isEqualTo(1);
        verify(mail).send(any(SimpleMailMessage.class));
        assertThat(notify.status().mailTo()).containsExactly("qa@test.invalid", "qso@test.invalid");
        assertThat(notify.status().wecomConfigured()).isFalse();

        doThrow(new MailSendException("连接失败")).when(mail).send(any(SimpleMailMessage.class));
        NotifyService.SendResult failed = notify.sendNow();
        assertThat(failed.failed()).isEqualTo(1);
        assertThat(notifyLogs.findTop50ByOrderByIdDesc().get(0).getError()).contains("连接失败");
    }

    @Test
    @Transactional
    @WithMockUser(username = "admin", roles = "ADMIN")
    void personalMailOnlyCarriesTodosForThatRole() {
        AppUser qso = user("qso1");
        qso.setEmail("qso-person@test.invalid");
        AppUser dealer = user("dealer1");
        dealer.setEmail("dealer@test.invalid");
        AppUser viewer = user("viewer1");
        viewer.setEmail("viewer@test.invalid");
        // 已在全量收件人里的不重复发
        AppUser qa = user("qa1");
        qa.setEmail("QA@test.invalid");
        users.saveAll(List.of(qso, dealer, viewer, qa));

        var people = notify.status().people();
        // 门户账号不收，只读账号没有相关事项，qa 已在全量里
        assertThat(people).extracting(NotifyService.PersonView::email).containsExactly("qso-person@test.invalid");
        // 配方待签批只有 QSO 的事，电商出库失败不是
        assertThat(people.get(0).itemCount()).isLessThan(notify.digest().items().size());

        NotifyService.SendResult r = notify.sendNow();
        assertThat(r.sent()).isEqualTo(2);
        var captor = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail, org.mockito.Mockito.times(2)).send(captor.capture());
        SimpleMailMessage personal = captor.getAllValues().stream()
                .filter(m -> List.of(m.getTo()).contains("qso-person@test.invalid")).findFirst().orElseThrow();
        assertThat(personal.getText()).contains("配方变更待签批").doesNotContain("电商订单出库失败");
        assertThat(notifyLogs.findTop50ByOrderByIdDesc()).extracting(l -> l.getRecipient()).contains("qso-person@test.invalid");
    }

    private static final class DashboardTodoKind {
        static String of(com.cosmetic.scm.dashboard.DashboardService.Todo t) {
            return t.kind();
        }
    }
}
