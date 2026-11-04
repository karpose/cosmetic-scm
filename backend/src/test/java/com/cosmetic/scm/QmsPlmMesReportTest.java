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
import com.cosmetic.scm.domain.CapaSource;
import com.cosmetic.scm.domain.CapaStatus;
import com.cosmetic.scm.domain.CapaType;
import com.cosmetic.scm.domain.CaseStatus;
import com.cosmetic.scm.domain.ClearancePhase;
import com.cosmetic.scm.domain.Decision;
import com.cosmetic.scm.domain.DeviationArea;
import com.cosmetic.scm.domain.DeviationLevel;
import com.cosmetic.scm.domain.FilingType;
import com.cosmetic.scm.domain.FormulaStatus;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.MoStatus;
import com.cosmetic.scm.domain.MoType;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.RetainStatus;
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
import com.cosmetic.scm.mes.MesService;
import com.cosmetic.scm.notify.NotificationLogRepository;
import com.cosmetic.scm.notify.NotifyService;
import com.cosmetic.scm.plm.FilingService;
import com.cosmetic.scm.plm.FormulaService;
import com.cosmetic.scm.plm.FormulaVersionRepository;
import com.cosmetic.scm.production.MfgOrder;
import com.cosmetic.scm.production.MfgOrderRepository;
import com.cosmetic.scm.production.ProductionService;
import com.cosmetic.scm.qms.DeviationService;
import com.cosmetic.scm.qms.RetainSample;
import com.cosmetic.scm.qms.RetainSampleRepository;
import com.cosmetic.scm.qms.RetainService;
import com.cosmetic.scm.qms.SupplierAuditService;
import com.cosmetic.scm.quality.QualityService;
import com.cosmetic.scm.report.ReportService;
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
        assertThatThrownBy(() -> mes.recordStep(mo.getId(), 2, ok)).hasMessageContaining("实测值");
        mes.recordStep(mo.getId(), 2, new MesService.StepReq(new BigDecimal("75"), null));
        mes.recordStep(mo.getId(), 3, new MesService.StepReq(new BigDecimal("6.2"), null));
        mes.recordStep(mo.getId(), 4, new MesService.StepReq(new BigDecimal("50.5"), null));
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
        assertThatThrownBy(() -> mes.recordStep(mo.getId(), 2, new MesService.StepReq(new BigDecimal("85"), null))).hasMessageContaining("超出限度");
        var r = mes.recordStep(mo.getId(), 2, new MesService.StepReq(new BigDecimal("85"), "温控失灵，已停机调整"));
        var step = r.steps().get(1);
        assertThat(step.ipcPass()).isFalse();
        assertThat(step.deviationNo()).startsWith("DV-");
        mes.recordStep(mo.getId(), 3, new MesService.StepReq(new BigDecimal("6"), null));
        mes.recordStep(mo.getId(), 4, new MesService.StepReq(new BigDecimal("50"), null));
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
            ReportService.Export e = reports.export(key);
            assertThat(e.fileName()).endsWith(".xlsx");
            assertThat(unzip(e.content()).get("xl/worksheets/sheet1.xml")).contains("<row r=\"1\">");
        }
        assertThat(unzip(reports.export("stock").content()).get("xl/worksheets/sheet1.xml")).contains("烟酰胺精华液");
        assertThat(unzip(reports.export("audit").content()).get("xl/worksheets/sheet1.xml")).contains("导出报表");
        assertThatThrownBy(() -> reports.export("nope")).hasMessageContaining("不存在");
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

    private static final class DashboardTodoKind {
        static String of(com.cosmetic.scm.dashboard.DashboardService.Todo t) {
            return t.kind();
        }
    }
}
