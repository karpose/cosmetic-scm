package com.cosmetic.scm.golive;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.FilingStatus;
import com.cosmetic.scm.domain.FormulaStatus;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.Role;
import com.cosmetic.scm.domain.SourceType;
import com.cosmetic.scm.domain.WarehouseKind;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.ItemRepository;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.PartnerRepository;
import com.cosmetic.scm.master.WarehouseRepository;
import com.cosmetic.scm.mes.EquipmentRepository;
import com.cosmetic.scm.mes.RoutingStepRepository;
import com.cosmetic.scm.plm.ComplianceService;
import com.cosmetic.scm.plm.FormulaVersionRepository;
import com.cosmetic.scm.plm.ProductFilingRepository;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.UserRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 上线前检查（SOP-09）：把“还缺什么才能用”直接查出来。很多缺口要到用的时候才暴露：
 * 没有质量安全负责人账号，系统就没人能上市放行；没有退货隔离区，退货就登记不了。
 * 只读，每次打开实时计算。BLOCK 不处理主线走不通；WARN 建议处理；OK 没问题。
 */
@Service
@RequiredArgsConstructor
public class GoLiveService {

    private static final int SAMPLE = 5;

    private final UserRepository users;
    private final WarehouseRepository warehouses;
    private final ItemRepository items;
    private final PartnerRepository partners;
    private final FormulaVersionRepository formulas;
    private final ProductFilingRepository filings;
    private final RoutingStepRepository routing;
    private final EquipmentRepository equipment;
    private final BatchRepository batches;
    private final TimeProvider time;
    private final com.cosmetic.scm.master.RegulatedSubstanceRepository regulated;
    private final com.cosmetic.scm.master.RegulatoryService regulatory;
    private final ComplianceService complianceDocs;
    private final com.cosmetic.scm.plm.StabilityService stability;
    private final com.cosmetic.scm.quality.QualitySpecService qualitySpecs;
    private final com.cosmetic.scm.qms.GmpSelfCheckRepository selfChecks;
    private final com.cosmetic.scm.plm.DocControlService docControl;

    @Value("${scm.demo-data:false}")
    private boolean demoData;
    @Value("${scm.notify.mail-to:}")
    private String mailTo;
    @Value("${scm.notify.wecom-webhook:}")
    private String wecom;

    /** state: OK / WARN / BLOCK */
    public record Check(String group, String title, String state, String detail, String route) {
    }

    public record Checklist(List<Check> checks, long blocking, long warnings) {
    }

    @Transactional(readOnly = true)
    public Checklist checklist() {
        List<Check> c = new ArrayList<>();
        accounts(c);
        masterData(c);
        formulas(c);
        compliance(c);
        production(c);
        stockAndOthers(c);
        return new Checklist(c, c.stream().filter(x -> "BLOCK".equals(x.state())).count(),
                c.stream().filter(x -> "WARN".equals(x.state())).count());
    }

    private void accounts(List<Check> c) {
        String g = "账号";
        c.add(demoData
                ? new Check(g, "示例数据", "BLOCK", "示例数据和示例账号（密码公开）还开着，正式上线前把 SCM_DEMO_DATA 设为 false，并用空库部署", null)
                : new Check(g, "示例数据", "OK", "已关闭", null));
        List<AppUser> active = users.findAll().stream().filter(AppUser::isEnabled).toList();
        c.add(roleCheck(active, Role.QSO, "质量安全负责人账号", "没有的话，成品无法上市放行、配方变更无法生效、期初库存无法导入"));
        c.add(roleCheck(active, Role.QA, "质量检验账号", "没有的话，物料无法检验放行"));
        c.add(roleCheck(active, Role.WAREHOUSE, "仓库账号", "没有的话，要由其它岗位代为操作收发货"));
        long noEmail = active.stream().filter(u -> !u.isPortal() && u.getEmail() == null).count();
        c.add(noEmail > 0 && mailTo.isBlank() && wecom.isBlank()
                ? new Check(g, "每日提醒", "WARN", "还没有配置邮件或企业微信，到期事项只能在总览里看到", "/notifications")
                : new Check(g, "每日提醒", "OK", wecom.isBlank() && mailTo.isBlank() ? "账号已填邮箱" : "已配置", "/notifications"));
    }

    private Check roleCheck(List<AppUser> active, Role role, String title, String why) {
        long n = active.stream().filter(u -> u.hasRole(role)).count();
        boolean blocking = role != Role.WAREHOUSE;
        return n > 0 ? new Check("账号", title, "OK", n + " 个", "/users")
                : new Check("账号", title, blocking ? "BLOCK" : "WARN", why, "/users");
    }

    private void masterData(List<Check> c) {
        String g = "主数据";
        LocalDate today = time.today();
        c.add(warehouses.findByKind(WarehouseKind.OWN).isEmpty()
                ? new Check(g, "自有仓", "BLOCK", "至少要有一个自有仓才能收货和发料", "/master")
                : new Check(g, "自有仓", "OK", warehouses.findByKind(WarehouseKind.OWN).size() + " 个", "/master"));
        c.add(warehouses.findByKind(WarehouseKind.RETURN).isEmpty()
                ? new Check(g, "退货隔离区", "BLOCK", "没有的话，销售退货登记不了。新增一个类型为“退货隔离区”的仓库", "/master")
                : new Check(g, "退货隔离区", "OK", "已建", "/master"));
        List<Item> allItems = items.findAll().stream().filter(Item::isActive).toList();
        List<Item> fg = allItems.stream().filter(i -> i.getItemType() == ItemType.FG).toList();
        c.add(allItems.isEmpty() ? new Check(g, "物料和成品", "BLOCK", "还没有导入物料", "/master")
                : new Check(g, "物料和成品", fg.isEmpty() ? "BLOCK" : "OK", "物料 " + (allItems.size() - fg.size()) + " 个，成品 " + fg.size() + " 个", "/master"));
        List<Item> noCost = allItems.stream().filter(i -> i.getItemType() != ItemType.FG && i.getStdCost() == null).toList();
        boolean noMaterials = allItems.stream().allMatch(i -> i.getItemType() == ItemType.FG);
        // 一个都没有时不能写“都已填写”，看着像已经检查通过了
        c.add(noCost.isEmpty() ? new Check(g, "标准成本", "OK", noMaterials ? "还没有物料，导入后再检查" : "物料都已填写", "/master")
                : new Check(g, "标准成本", "WARN", noCost.size() + " 个物料没填，看板金额会偏低：" + codes(noCost), "/master"));

        List<Partner> all = partners.findAll();
        for (PartnerKind kind : List.of(PartnerKind.SUPPLIER, PartnerKind.CUSTOMER)) {
            String title = kind.getLabel();
            long n = all.stream().filter(p -> p.getKind() == kind).count();
            c.add(n == 0 ? new Check(g, title, "BLOCK", "还没有导入" + title, "/master") : new Check(g, title, "OK", n + " 家", "/master"));
        }
        List<Partner> factories = all.stream().filter(p -> p.getKind() == PartnerKind.OEM || p.getKind() == PartnerKind.OWN_FACTORY).toList();
        c.add(factories.isEmpty() ? new Check(g, "生产方", "BLOCK", "至少要有一个受托厂或自有工厂才能下生产单", "/master")
                : new Check(g, "生产方", "OK", factories.size() + " 家", "/master"));
        List<Partner> bad = all.stream().filter(p -> p.getKind() != PartnerKind.CUSTOMER && !p.usable(today)).toList();
        boolean noVendors = all.stream().allMatch(p -> p.getKind() == PartnerKind.CUSTOMER);
        c.add(bad.isEmpty() ? new Check(g, "供应商和受托厂资质", "OK", noVendors ? "还没有供应商和受托厂，导入后再检查" : "都在合格名录内且未过期", "/master")
                : new Check(g, "供应商和受托厂资质", "WARN", bad.size() + " 家不能下单（不在名录或资质过期）：" + names(bad), "/master"));
        List<Partner> noContact = all.stream().filter(p -> p.getKind() == PartnerKind.CUSTOMER && (p.getContact() == null || p.getAddress() == null)).toList();
        boolean noCustomers = all.stream().noneMatch(p -> p.getKind() == PartnerKind.CUSTOMER);
        c.add(noContact.isEmpty() ? new Check(g, "客户联系方式", "OK", noCustomers ? "还没有客户，导入后再检查" : "都已填写", "/master")
                : new Check(g, "客户联系方式", "WARN", noContact.size() + " 家缺联系人或地址，召回时要用：" + names(noContact), "/master"));
    }

    private void formulas(List<Check> c) {
        String g = "配方与备案";
        List<Item> fg = items.findAll().stream().filter(i -> i.isActive() && i.getItemType() == ItemType.FG).toList();
        List<Item> noFormula = fg.stream().filter(i -> formulas.findFirstByFgItemIdAndStatus(i.getId(), FormulaStatus.EFFECTIVE).isEmpty()).toList();
        c.add(noFormula.isEmpty() ? new Check(g, "成品配方", "OK", fg.isEmpty() ? "还没有成品，导入后再检查" : "都已录入", "/master")
                : new Check(g, "成品配方", "BLOCK", noFormula.size() + " 个成品没有配方，不能下生产单：" + codes(noFormula), "/master"));
        List<Item> noFiling = fg.stream().filter(i -> i.getFilingNo() == null).toList();
        c.add(noFiling.isEmpty() ? new Check(g, "备案编号", "OK", fg.isEmpty() ? "还没有成品，导入后再检查" : "都已登记", "/formulas?tab=filings")
                : new Check(g, "备案编号", "BLOCK", noFiling.size() + " 个成品没有备案编号，不能上市放行：" + codes(noFiling), "/formulas?tab=filings"));
        long unchecked = filings.findByStatus(FilingStatus.FILED).stream().filter(f -> f.getFiledDate() == null).count();
        c.add(unchecked == 0 ? new Check(g, "备案记录", "OK", fg.isEmpty() ? "还没有成品，导入后再检查" : "类型和日期都已核对", "/formulas?tab=filings")
                : new Check(g, "备案记录", "WARN", unchecked + " 条是自动生成的，请核对类型和日期；特殊化妆品要补注册证有效期，否则到期提醒不生效", "/formulas?tab=filings"));
    }

    private void compliance(List<Check> c) {
        String g = "合规资料";
        long rules = regulated.countByActiveTrue();
        c.add(rules == 0 ? new Check(g, "法规目录", "WARN", "还没有导入。现在禁用和限用只靠物料上人工标的，请按官方发布的版本导入", "/compliance?tab=regulatory")
                : new Check(g, "法规目录", "OK", rules + " 条。法规更新时要重新导入", "/compliance?tab=regulatory"));
        long violations = regulatory.violations().size();
        if (violations > 0) {
            c.add(new Check(g, "配方和法规", "BLOCK", violations + " 处生效配方不符合法规目录", "/compliance?tab=regulatory"));
        }
        List<ComplianceService.ProductStatus> products = complianceDocs.statuses().stream().filter(p -> p.versionId() != null).toList();
        long incomplete = products.stream().filter(p -> !p.complete()).count();
        c.add(incomplete == 0 ? new Check(g, "产品合规资料", "OK", products.isEmpty() ? "还没有配方，导入后再检查" : "安全评估和标签都已签批", "/compliance")
                : new Check(g, "产品合规资料", "WARN", incomplete + " 个成品缺已签批的安全评估或标签", "/compliance"));
        java.util.Set<Long> withSpec = qualitySpecs.itemsWithSpec();
        c.add(withSpec.isEmpty() ? new Check(g, "质量标准", "WARN", "还没有生效的质量标准，现在放行只看检验报告编号。录入标准后，有标准的物料放行前要逐项录检验结果", "/specs")
                : new Check(g, "质量标准", "OK", withSpec.size() + " 个物料有生效标准，放行前要逐项录检验结果；其余按检验报告编号放行", "/specs"));
        List<com.cosmetic.scm.plm.StabilityService.ProductCoverage> coverage = stability.coverage();
        long noBasis = coverage.stream().filter(p -> !"OK".equals(p.state())).count();
        c.add(noBasis == 0 ? new Check(g, "稳定性依据", "OK", coverage.isEmpty() ? "还没有配方，导入后再检查" : "保质期都有已签批的结论", "/stability?tab=products")
                : new Check(g, "稳定性依据", "WARN", noBasis + " 个成品的保质期没有足够的稳定性结论（已有报告的可以按实际开始日期补录）", "/stability?tab=products"));
        // 检查要点要求建立的制度：可选的（提供物料时才要）不算缺
        List<com.cosmetic.scm.plm.ProcedureCoverage.Row> procs = docControl.procedureCoverage();
        List<String> missing = procs.stream().filter(r -> !r.optional() && r.state() != com.cosmetic.scm.plm.ProcedureCoverage.State.ESTABLISHED).map(r -> r.label()).toList();
        c.add(missing.isEmpty() ? new Check(g, "必备制度", "OK", "检查要点要求的制度都有生效的受控文件", "/docs?tab=procedures")
                : new Check(g, "必备制度", "WARN", missing.size() + " 项制度还没有生效的受控文件：" + String.join("、", missing.subList(0, Math.min(6, missing.size())))
                        + (missing.size() > 6 ? " 等" : ""), "/docs?tab=procedures"));
        // 每年要按检查要点（委托生产版）自查一次；上线前不拦，只提示
        var lastCheck = selfChecks.findFirstByStatusOrderByApprovedAtDesc(com.cosmetic.scm.domain.SelfCheckStatus.APPROVED);
        boolean recent = lastCheck.isPresent() && lastCheck.get().getApprovedAt().toLocalDate().plusMonths(12).isAfter(time.today());
        c.add(recent ? new Check(g, "年度 GMP 自查", "OK", "最近一次 " + lastCheck.get().getCheckNo() + " 于 " + lastCheck.get().getApprovedAt().toLocalDate() + " 签批，结论："
                        + lastCheck.get().getConclusion().getLabel(), "/self-checks")
                : new Check(g, "年度 GMP 自查", "WARN", lastCheck.isEmpty() ? "还没有按检查要点（委托生产版）做过自查，上线后一年内要做一次"
                        : "最近一次自查已超过 12 个月", "/self-checks"));
    }

    private void production(List<Check> c) {
        String g = "生产";
        boolean ownFactory = partners.findAll().stream().anyMatch(p -> p.getKind() == PartnerKind.OWN_FACTORY);
        if (ownFactory) {
            List<Item> noRouting = items.findAll().stream().filter(i -> i.isActive() && i.getItemType() == ItemType.FG)
                    .filter(i -> routing.findByFgItemIdOrderByStepNo(i.getId()).isEmpty()).toList();
            c.add(noRouting.isEmpty() ? new Check(g, "工艺路线", "OK", "成品都已维护", "/formulas?tab=routing")
                    : new Check(g, "工艺路线", "WARN", noRouting.size() + " 个成品没有工艺路线，自产时不会生成批生产记录（全部委托生产的可忽略）：" + codes(noRouting), "/formulas?tab=routing"));
        }
        long total = equipment.count();
        long overdue = equipment.findAll().stream().filter(e -> e.isNeedsCalibration() && (e.getNextDue() == null || e.getNextDue().isBefore(time.today()))).count();
        c.add(total == 0 ? new Check(g, "设备台账", ownFactory ? "WARN" : "OK", ownFactory ? "还没有维护设备，工序无法要求记录设备" : "没有自有工厂，可以不维护", "/qms?tab=equipment")
                : overdue > 0 ? new Check(g, "设备台账", "WARN", overdue + " 台校准已过期或没有校准记录，不能用于批记录", "/qms?tab=equipment")
                : new Check(g, "设备台账", "OK", total + " 台，校准都有效", "/qms?tab=equipment"));
    }

    private void stockAndOthers(List<Check> c) {
        long opening = batches.countBySourceType(SourceType.OPEN);
        c.add(opening > 0 ? new Check("库存", "期初库存", "OK", "已导入 " + opening + " 个批次", "/inventory-ops")
                : new Check("库存", "期初库存", "WARN", "还没有导入。仓库里有现货的话，按盘点表在“库存作业”导入，不要用虚构单据录入", "/inventory-ops"));
    }

    private static String codes(List<Item> list) {
        String s = list.stream().limit(SAMPLE).map(Item::getCode).collect(Collectors.joining("、"));
        return list.size() > SAMPLE ? s + " 等" : s;
    }

    private static String names(List<Partner> list) {
        String s = list.stream().limit(SAMPLE).map(Partner::getName).collect(Collectors.joining("、"));
        return list.size() > SAMPLE ? s + " 等" : s;
    }
}
