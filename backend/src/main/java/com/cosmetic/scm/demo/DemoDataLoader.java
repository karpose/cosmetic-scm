package com.cosmetic.scm.demo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Supplier;

import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.complaint.ComplaintService;
import com.cosmetic.scm.complaint.ComplaintService.ComplaintReq;
import com.cosmetic.scm.dealer.DealerService;
import com.cosmetic.scm.domain.AuditResult;
import com.cosmetic.scm.domain.AuditType;
import com.cosmetic.scm.domain.CapaSource;
import com.cosmetic.scm.domain.CapaType;
import com.cosmetic.scm.domain.ComplaintCategory;
import com.cosmetic.scm.domain.DeviationArea;
import com.cosmetic.scm.domain.DeviationLevel;
import com.cosmetic.scm.domain.FilingType;
import com.cosmetic.scm.domain.OemStage;
import com.cosmetic.scm.domain.Decision;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.Role;
import com.cosmetic.scm.domain.Severity;
import com.cosmetic.scm.domain.SupplyMode;
import com.cosmetic.scm.domain.WarehouseKind;
import com.cosmetic.scm.integration.IntegrationService;
import com.cosmetic.scm.inventory.InventoryService;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.ItemRepository;
import com.cosmetic.scm.master.MasterDtos.BomLineReq;
import com.cosmetic.scm.master.MasterDtos.BomReq;
import com.cosmetic.scm.master.MasterDtos.ItemReq;
import com.cosmetic.scm.master.MasterDtos.PartnerReq;
import com.cosmetic.scm.master.MasterDtos.PartnerView;
import com.cosmetic.scm.master.MasterDtos.WarehouseReq;
import com.cosmetic.scm.master.MasterDtos.WarehouseView;
import com.cosmetic.scm.master.MasterService;
import com.cosmetic.scm.oemportal.OemPortalService;
import com.cosmetic.scm.production.ProductionService;
import com.cosmetic.scm.production.ProductionService.CompleteReq;
import com.cosmetic.scm.production.ProductionService.CreateMoReq;
import com.cosmetic.scm.production.ProductionService.ExtMaterialReq;
import com.cosmetic.scm.production.ProductionService.MoView;
import com.cosmetic.scm.purchase.PurchaseService;
import com.cosmetic.scm.purchase.PurchaseService.CreatePoReq;
import com.cosmetic.scm.purchase.PurchaseService.PoLineReq;
import com.cosmetic.scm.purchase.PurchaseService.PoLineView;
import com.cosmetic.scm.purchase.PurchaseService.PoView;
import com.cosmetic.scm.purchase.PurchaseService.ReceiveLineReq;
import com.cosmetic.scm.purchase.PurchaseService.ReceiveReq;
import com.cosmetic.scm.quality.QualityService;
import com.cosmetic.scm.quality.QualityService.MaterialDecisionReq;
import com.cosmetic.scm.quality.QualityService.ReleaseReq;
import com.cosmetic.scm.sales.SalesService;
import com.cosmetic.scm.sales.SalesService.SoLineReq;
import com.cosmetic.scm.sales.SalesService.SoReq;
import com.cosmetic.scm.salesreturn.ReturnService;
import com.cosmetic.scm.salesreturn.ReturnService.ReturnLineReq;
import com.cosmetic.scm.salesreturn.ReturnService.ReturnReq;
import com.cosmetic.scm.mes.MesService;
import com.cosmetic.scm.plm.FilingService;
import com.cosmetic.scm.plm.FormulaService;
import com.cosmetic.scm.qms.DeviationService;
import com.cosmetic.scm.qms.SupplierAuditService;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.serial.SerialService;
import com.cosmetic.scm.security.UserRepository;
import com.cosmetic.scm.security.UserService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 示例数据：只在 scm.demo-data=true 且库里还没有物料时运行。
 * 通过真实业务服务走一遍主线（不直接写表），所以示例数据同样经过全部业务校验。
 * 所有日期都相对“今天”生成，任何时候启动都能得到一致的演示状态。名称均带“（示例）”。
 */
@Component
@Order(2)
@ConditionalOnProperty(name = "scm.demo-data", havingValue = "true")
@RequiredArgsConstructor
public class DemoDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataLoader.class);
    public static final String DEMO_PASSWORD = "Demo@12345";

    private final ItemRepository itemRepository;
    private final UserRepository users;
    private final UserService userService;
    private final MasterService master;
    private final PurchaseService purchase;
    private final QualityService quality;
    private final ProductionService production;
    private final SalesService sales;
    private final ReturnService returns;
    private final ComplaintService complaints;
    private final OemPortalService oemPortal;
    private final DealerService dealers;
    private final IntegrationService integration;
    private final SerialService serials;
    private final FormulaService formulas;
    private final FilingService filings;
    private final DeviationService deviations;
    private final SupplierAuditService supplierAudits;
    private final MesService mes;
    private final InventoryService inventory;
    private final TimeProvider time;

    @Override
    public void run(ApplicationArguments args) {
        if (itemRepository.count() > 0) {
            return;
        }
        try {
            load();
            log.warn("已写入示例数据。示例账号密码均为 {}：purchase1 / prod1 / qa1 / qso1 / sales1 / viewer1；门户：oem1（禾川）/ dealer1（华东经销商）",
                    DEMO_PASSWORD);
        } catch (RuntimeException e) {
            log.error("示例数据写入中断，系统仍可正常使用：{}", e.getMessage(), e);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void load() {
        LocalDate t = time.today();
        user("purchase1", "采购 · 示例", Role.PURCHASE, Role.WAREHOUSE);
        user("prod1", "生产计划 · 示例", Role.PRODUCTION, Role.WAREHOUSE);
        user("qa1", "质量检验 · 示例", Role.QA);
        user("qso1", "质量安全负责人 · 示例", Role.QSO, Role.QA);
        user("sales1", "销售 · 示例", Role.SALES);
        user("viewer1", "只读 · 示例", Role.VIEWER);
        AppUser qa = users.findByUsername("qa1").orElseThrow();
        AppUser qso = users.findByUsername("qso1").orElseThrow();

        // ---------- 主数据 ----------
        Item nia = as("admin", () -> rm("RM-NIA-001", "烟酰胺", "Niacinamide", null, 24, "180"));
        Item gly = as("admin", () -> rm("RM-GLY-002", "甘油", "Glycerin", null, 36, "12"));
        Item ha = as("admin", () -> rm("RM-HA-003", "透明质酸钠", "Sodium Hyaluronate", null, 24, "3600"));
        Item phe = as("admin", () -> rm("RM-PHE-004", "苯氧乙醇", "Phenoxyethanol", new BigDecimal("1.0"), 36, "45"));
        Item squ = as("admin", () -> rm("RM-SQU-005", "角鲨烷", "Squalane", null, 24, "260"));
        Item btl = as("admin", () -> pk("PK-BTL-001", "30ml 滴管精华瓶", "2.80"));
        Item box2 = as("admin", () -> pk("PK-BOX-002", "精华彩盒 v2", "1.20"));
        Item jar = as("admin", () -> pk("PK-JAR-003", "50g 面霜瓶", "3.50"));
        Item box4 = as("admin", () -> pk("PK-BOX-004", "面霜彩盒 v1", "1.50"));
        Item serum = as("admin", () -> fg("FG-SER-001", "烟酰胺精华液 30ml", "示例备案编号-0001", "30", "199"));
        Item cream = as("admin", () -> fg("FG-CRM-002", "角鲨烷修护面霜 50g", "示例备案编号-0002", "50", "259"));

        PartnerView hc = as("admin", () -> partner("SUP-HC", "华澄化工原料（示例）", PartnerKind.SUPPLIER,
                "示例质量体系证书 QS-118", t.plusDays(300), null, null, null, 0, true));
        PartnerView mp = as("admin", () -> partner("SUP-MP", "明瓶包装（示例）", PartnerKind.SUPPLIER,
                "示例质量体系证书 ISO-2207", t.plusDays(43), null, null, null, 0, false));
        PartnerView a3 = as("admin", () -> partner("OEM-A3", "禾川生物（示例）", PartnerKind.OEM,
                "示例生产许可证 0003", t.plusDays(240), "一般液态单元（护肤水类）；膏霜乳液单元", "A3", null, 0, false));
        PartnerView own = as("admin", () -> partner("OWN-01", "自有工厂（示例）", PartnerKind.OWN_FACTORY,
                "示例生产许可证 0101", t.plusDays(500), "膏霜乳液单元（护肤清洁类）", "OW", null, 0, false));
        PartnerView tm = as("admin", () -> partner("CUS-TM", "天猫旗舰店（示例）", PartnerKind.CUSTOMER, null, null, null, null, "电商", 50, false));
        PartnerView jd = as("admin", () -> partner("CUS-JD", "京东自营入仓（示例）", PartnerKind.CUSTOMER, null, null, null, null, "电商平台入仓", 67, false));
        as("admin", () -> partner("CUS-HD", "华东经销商（示例）", PartnerKind.CUSTOMER, null, null, null, null, "线下经销", 50, false));
        as("admin", () -> partner("CUS-SY", "私域小程序商城（示例）", PartnerKind.CUSTOMER, null, null, null, null, "私域", 33, false));

        WarehouseView main = as("admin", () -> master.createWarehouse(new WarehouseReq("WH-MAIN", "华东主仓", WarehouseKind.OWN, null)));
        as("admin", () -> master.createWarehouse(new WarehouseReq("WH-A3", "禾川寄存仓", WarehouseKind.OEM, a3.id())));
        WarehouseView rtn = as("admin", () -> master.createWarehouse(new WarehouseReq("WH-RTN", "退货隔离区", WarehouseKind.RETURN, null)));

        as("admin", () -> master.saveBom(serum.getId(), new BomReq(List.of(
                new BomLineReq(nia.getId(), new BigDecimal("0.0015"), SupplyMode.BRAND),
                new BomLineReq(gly.getId(), new BigDecimal("0.0015"), SupplyMode.OEM),
                new BomLineReq(ha.getId(), new BigDecimal("0.00003"), SupplyMode.BRAND),
                new BomLineReq(phe.getId(), new BigDecimal("0.00024"), SupplyMode.OEM),
                new BomLineReq(btl.getId(), BigDecimal.ONE, SupplyMode.BRAND),
                new BomLineReq(box2.getId(), BigDecimal.ONE, SupplyMode.BRAND)))));
        as("admin", () -> master.saveBom(cream.getId(), new BomReq(List.of(
                new BomLineReq(squ.getId(), new BigDecimal("0.005"), SupplyMode.BRAND),
                new BomLineReq(gly.getId(), new BigDecimal("0.0025"), SupplyMode.BRAND),
                new BomLineReq(phe.getId(), new BigDecimal("0.0004"), SupplyMode.BRAND),
                new BomLineReq(jar.getId(), BigDecimal.ONE, SupplyMode.BRAND),
                new BomLineReq(box4.getId(), BigDecimal.ONE, SupplyMode.BRAND)))));

        // ---------- 采购收货 ----------
        List<String> received = new java.util.ArrayList<>();
        received.addAll(buyAndReceive(hc.id(), main.id(), t.minusDays(60), null,
                line(nia, "30"), line(ha, "1"), line(squ, "60"), line(gly, "30"), line(phe, "5")));
        // 一批临近效期的旧苯氧乙醇：FEFO 会优先用掉它
        received.addAll(buyAndReceive(hc.id(), main.id(), t.minusDays(1020), t.plusDays(30), line(phe, "0.5")));
        received.addAll(buyAndReceive(mp.id(), main.id(), t.minusDays(40), null,
                line(btl, "20000"), line(box2, "20000"), line(jar, "10000"), line(box4, "10000")));
        for (String no : received) {
            Long id = inventory.batchByNo(no).getId();
            as("qa1", () -> quality.materialDecision(id, new MaterialDecisionReq(Decision.RELEASED,
                    "COA-" + no, "外观、含量与内控标准一致（示例）", DEMO_PASSWORD), qa));
        }

        // ---------- 委托生产精华液：已放行并发货 ----------
        MoView mo1 = as("prod1", () -> production.create(new CreateMoReq(a3.id(), serum.getId(), new BigDecimal("10000"), t.minusDays(16))));
        as("prod1", () -> production.issue(mo1.id()));
        MoView done1 = as("prod1", () -> production.complete(mo1.id(), new CompleteReq(new BigDecimal("9950"), t.minusDays(13),
                "HC-A-01", main.id(), true, null, List.of(
                new ExtMaterialReq(gly.getId(), "HC-GL-EXT-01", new BigDecimal("15")),
                new ExtMaterialReq(phe.getId(), "YX-PE-EXT-08", new BigDecimal("2.4"))))));
        Long fg1 = inventory.batchByNo(done1.outBatchNo()).getId();
        as("qso1", () -> quality.marketRelease(fg1, new ReleaseReq(Decision.RELEASED, "QC-" + done1.outBatchNo(),
                "受托厂放行记录及出厂检验报告已审核（示例）", true, true, true, true, 6, "品质部留样室 B-01", DEMO_PASSWORD), qso));
        as("sales1", () -> sales.create(new SoReq(jd.id(), main.id(), List.of(new SoLineReq(serum.getId(), new BigDecimal("2000"))))));
        as("sales1", () -> sales.create(new SoReq(tm.id(), main.id(), List.of(new SoLineReq(serum.getId(), new BigDecimal("1000"))))));

        // ---------- 第二批精华液：完工待上市放行 ----------
        MoView mo2 = as("prod1", () -> production.create(new CreateMoReq(a3.id(), serum.getId(), new BigDecimal("5000"), t.minusDays(4))));
        as("prod1", () -> production.issue(mo2.id()));
        as("prod1", () -> production.complete(mo2.id(), new CompleteReq(new BigDecimal("4980"), t.minusDays(2),
                "HC-A-02", main.id(), true, null, List.of(
                new ExtMaterialReq(gly.getId(), "HC-GL-EXT-01", new BigDecimal("7.5")),
                new ExtMaterialReq(phe.getId(), "YX-PE-EXT-08", new BigDecimal("1.2"))))));

        // ---------- 一批 14 个月前生产的老精华液（剩余效期约 61%）：京东 ≥67% 会排除它，天猫 ≥50% 会优先用它 ----------
        MoView mo0 = as("prod1", () -> production.create(new CreateMoReq(a3.id(), serum.getId(), new BigDecimal("1000"), t.minusDays(425))));
        as("prod1", () -> production.issue(mo0.id()));
        MoView done0 = as("prod1", () -> production.complete(mo0.id(), new CompleteReq(new BigDecimal("1000"), t.minusDays(420),
                "HC-A-00", main.id(), true, null, List.of(
                new ExtMaterialReq(gly.getId(), "HC-GL-EXT-00", new BigDecimal("1.5")),
                new ExtMaterialReq(phe.getId(), "YX-PE-EXT-05", new BigDecimal("0.24"))))));
        Long fg0 = inventory.batchByNo(done0.outBatchNo()).getId();
        as("qso1", () -> quality.marketRelease(fg0, new ReleaseReq(Decision.RELEASED, "QC-" + done0.outBatchNo(),
                "受托厂放行记录及出厂检验报告已审核（示例）", true, true, true, true, 6, "品质部留样室 B-02", DEMO_PASSWORD), qso));

        // ---------- 天猫退回 20 瓶外箱破损的精华液：在隔离区等质量处置 ----------
        as("sales1", () -> returns.create(new ReturnReq(tm.id(), rtn.id(), null, "物流外箱破损，客户拒收（示例）",
                List.of(new ReturnLineReq(fg1, new BigDecimal("20"))))));

        // ---------- 一条消费者不良反应投诉：待调查、待上报 ----------
        as("sales1", () -> complaints.create(new ComplaintReq(t.minusDays(3), "电商平台", tm.id(), "王女士（示例）138****0000",
                serum.getId(), done1.outBatchNo(), ComplaintCategory.ADVERSE, Severity.NORMAL,
                "使用两天后面部轻微泛红、刺痛，已停用（示例）")));

        // ---------- 一张部分收货的采购单，产生待检批次 ----------
        PoView po4 = as("purchase1", () -> purchase.create(new CreatePoReq(hc.id(), List.of(line(nia, "20"), line(gly, "50")))));
        PoLineView niaLine = po4.lines().get(0);
        as("purchase1", () -> purchase.receive(po4.id(), new ReceiveReq(List.of(new ReceiveLineReq(niaLine.id(),
                new BigDecimal("20"), "HC-NA-NEW-03", t.minusDays(18), null, main.id())))));

        // ---------- 门户账号：禾川的代工厂门户、华东经销商的订货门户 ----------
        PartnerView hd = as("admin", () -> master.listPartners().stream().filter(p -> "CUS-HD".equals(p.code())).findFirst().orElseThrow());
        if (!users.existsByUsername("oem1")) {
            userService.newUser("oem1", "禾川生物 · 生产计划（示例）", DEMO_PASSWORD, EnumSet.of(Role.OEM_PORTAL), a3.id(), false);
        }
        if (!users.existsByUsername("dealer1")) {
            userService.newUser("dealer1", "华东经销商 · 采购（示例）", DEMO_PASSWORD, EnumSet.of(Role.DEALER), hd.id(), false);
        }

        // ---------- 第三张委托单：已发料，受托厂已在门户接单排产，等它申报完工 ----------
        MoView mo4 = as("prod1", () -> production.create(new CreateMoReq(a3.id(), serum.getId(), new BigDecimal("3000"), t.plusDays(10))));
        as("prod1", () -> production.issue(mo4.id()));
        as("oem1", () -> oemPortal.addProgress(mo4.id(), new OemPortalService.ProgressReq(OemStage.CONFIRMED, t.plusDays(9), "已收到品牌方发料（示例）")));
        as("oem1", () -> oemPortal.addProgress(mo4.id(), new OemPortalService.ProgressReq(OemStage.SCHEDULED, null, "排在 3 号灌装线（示例）")));

        // ---------- 经销商在门户提交的一张订货单，等销售审核 ----------
        as("dealer1", () -> dealers.submit(new DealerService.OrderReq(t.plusDays(5), "补货（示例）",
                List.of(new DealerService.LineReq(serum.getId(), new BigDecimal("200"))))));

        // ---------- 天猫 ERP 推来一单面霜，此时还没有面霜库存：出库失败，面霜放行后可在“电商对接”里重试 ----------
        as("admin", () -> integration.receive(new IntegrationService.PushReq("TMALL", "CUS-TM", null, List.of(
                new IntegrationService.PushOrder("TM-DEMO-0001", t.atTime(10, 0),
                        List.of(new IntegrationService.PushLine("FG-CRM-002", new BigDecimal("2"))))))));

        // ---------- 第一批精华液生成 20 个一物一码，可在“一物一码”里导出、在扫码页查询 ----------
        as("qa1", () -> serials.generate(new SerialService.GenerateReq(done1.outBatchNo(), 20)));

        // ---------- 配方与备案：精华液有一个提高透明质酸钠用量的变更，备案还在途，所以暂时不能签批生效 ----------
        FormulaService.VersionView v2 = as("admin", () -> formulas.createDraft(serum.getId(), new FormulaService.DraftReq(
                "透明质酸钠由 0.1% 提到 0.17%，改善肤感（示例）", List.of(
                new BomLineReq(nia.getId(), new BigDecimal("0.0015"), SupplyMode.BRAND),
                new BomLineReq(gly.getId(), new BigDecimal("0.0015"), SupplyMode.OEM),
                new BomLineReq(ha.getId(), new BigDecimal("0.00005"), SupplyMode.BRAND),
                new BomLineReq(phe.getId(), new BigDecimal("0.00024"), SupplyMode.OEM),
                new BomLineReq(btl.getId(), BigDecimal.ONE, SupplyMode.BRAND),
                new BomLineReq(box2.getId(), BigDecimal.ONE, SupplyMode.BRAND)))));
        as("admin", () -> formulas.submit(v2.id()));
        as("admin", () -> filings.create(new FilingService.FilingReq(serum.getId(), FilingType.GENERAL, v2.id(), t.minusDays(2), "配方变更备案（示例）")));

        // ---------- 质量体系：供应商审核（明瓶快到期）、一条微小偏差和它的预防措施 ----------
        as("qa1", () -> supplierAudits.create(new SupplierAuditService.AuditReq(hc.id(), t.minusDays(60), AuditType.ROUTINE, 92, AuditResult.PASS,
                null, "质量部 张工（示例）", t.plusDays(305), DEMO_PASSWORD), qa));
        as("qa1", () -> supplierAudits.create(new SupplierAuditService.AuditReq(mp.id(), t.minusDays(340), AuditType.ROUTINE, 78, AuditResult.CONDITIONAL,
                "来料检验记录不完整，已要求整改（示例）", "质量部 张工（示例）", t.plusDays(25), DEMO_PASSWORD), qa));
        as("qa1", () -> supplierAudits.create(new SupplierAuditService.AuditReq(a3.id(), t.minusDays(120), AuditType.ROUTINE, 88, AuditResult.PASS,
                null, "质量安全负责人（示例）", t.plusDays(245), DEMO_PASSWORD), qa));
        DeviationService.DeviationView dv = as("prod1", () -> deviations.create(new DeviationService.DeviationReq(t.minusDays(1), DeviationArea.WAREHOUSE, DeviationLevel.MINOR,
                null, "华东主仓温湿度记录缺失一天（示例）", "记录仪电池耗尽，缺少一天的数据；前后两天均在规定范围内（示例）", "更换电池并补充人工记录（示例）", t.plusDays(14))));
        as("qa1", () -> deviations.createCapa(new DeviationService.CapaReq(CapaSource.DEVIATION, null, dv.id(), CapaType.PREVENTIVE,
                "把记录仪电量纳入每周点检（示例）", "仓库主管（示例）", t.plusDays(21))));

        // ---------- 自产面霜：有工艺路线；已计划，待发料。发料后在生产单明细里填批生产记录 ----------
        as("admin", () -> mes.saveRouting(cream.getId(), new MesService.RoutingReq(List.of(
                new MesService.RoutingStepReq("称量投料", "按配方逐项称量，双人核对物料和批号", true, null, null, null, null),
                new MesService.RoutingStepReq("乳化", "水相、油相分别升温后混合，均质 10 分钟", false, "乳化温度", new BigDecimal("70"), new BigDecimal("80"), "℃"),
                new MesService.RoutingStepReq("冷却", "搅拌冷却后加入防腐体系", false, "料体 pH", new BigDecimal("5.0"), new BigDecimal("7.0"), null),
                new MesService.RoutingStepReq("灌装", "首件确认后连续灌装，每 30 分钟抽检装量", true, "装量", new BigDecimal("49"), new BigDecimal("52"), "g"),
                new MesService.RoutingStepReq("包装贴标", "核对批号和限用日期后贴标装盒", false, null, null, null, null)))));
        as("prod1", () -> production.create(new CreateMoReq(own.id(), cream.getId(), new BigDecimal("8000"), t.plusDays(7))));
    }

    // ---------- 小工具 ----------

    private void user(String username, String name, Role... roles) {
        if (!users.existsByUsername(username)) {
            userService.newUser(username, name, DEMO_PASSWORD, EnumSet.copyOf(List.of(roles)));
        }
    }

    private <T> T as(String username, Supplier<T> action) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, null, List.of()));
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Item rm(String code, String name, String inci, BigDecimal limit, int shelf, String cost) {
        return master.createItem(new ItemReq(code, name, ItemType.RM, "kg", inci, limit, false, shelf, null, null, true,
                new BigDecimal(cost), null));
    }

    private Item pk(String code, String name, String cost) {
        return master.createItem(new ItemReq(code, name, ItemType.PK, "个", null, null, false, null, null, null, true,
                new BigDecimal(cost), null));
    }

    /** 成品不填标准成本，按 BOM 物料成本估算；只填指导价。 */
    private Item fg(String code, String name, String filing, String netG, String price) {
        return master.createItem(new ItemReq(code, name, ItemType.FG, "瓶", null, null, false, 36, filing, new BigDecimal(netG), true,
                null, new BigDecimal(price)));
    }

    private PartnerView partner(String code, String name, PartnerKind kind, String license, LocalDate expiry,
                                String scope, String prefix, String channel, int minShelf, boolean critical) {
        return master.createPartner(new PartnerReq(code, name, kind, license, expiry, scope, prefix, channel, minShelf,
                kind == PartnerKind.CUSTOMER ? "示例联系人" : null, null, true, critical));
    }

    private static PoLineReq line(Item item, String qty) {
        return new PoLineReq(item.getId(), new BigDecimal(qty));
    }

    /** 下采购单并全部收货，返回生成的批号。expDate 为空时按保质期推算。 */
    private List<String> buyAndReceive(Long supplierId, Long whId, LocalDate mfg, LocalDate exp, PoLineReq... lines) {
        PoView po = as("purchase1", () -> purchase.create(new CreatePoReq(supplierId, List.of(lines))));
        List<ReceiveLineReq> recv = po.lines().stream()
                .map(l -> new ReceiveLineReq(l.id(), l.qty(), "EXT-" + l.itemCode() + "-" + mfg.toString().replace("-", ""),
                        mfg, exp, whId))
                .toList();
        return as("purchase1", () -> purchase.receive(po.id(), new ReceiveReq(recv)));
    }
}
