package com.cosmetic.scm.qms;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.cosmetic.scm.audit.AuditRepository;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.complaint.Complaint;
import com.cosmetic.scm.complaint.ComplaintRepository;
import com.cosmetic.scm.domain.AuditResult;
import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.CaseStatus;
import com.cosmetic.scm.domain.ComplaintCategory;
import com.cosmetic.scm.domain.Decision;
import com.cosmetic.scm.domain.DocStatus;
import com.cosmetic.scm.domain.FilingStatus;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.MoStatus;
import com.cosmetic.scm.domain.MoType;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.QcStage;
import com.cosmetic.scm.domain.Role;
import com.cosmetic.scm.domain.SourceType;
import com.cosmetic.scm.inventory.Batch;
import com.cosmetic.scm.inventory.BatchRepository;
import com.cosmetic.scm.inventory.Stock;
import com.cosmetic.scm.inventory.StockRepository;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.plm.ControlledDoc;
import com.cosmetic.scm.plm.ControlledDocRepository;
import com.cosmetic.scm.plm.ProductFiling;
import com.cosmetic.scm.plm.ProductFilingRepository;
import com.cosmetic.scm.production.MfgOrder;
import com.cosmetic.scm.production.MfgOrderRepository;
import com.cosmetic.scm.quality.QualityDecision;
import com.cosmetic.scm.quality.QualityDecisionRepository;
import com.cosmetic.scm.recall.RecallRepository;
import com.cosmetic.scm.salesreturn.SalesReturnRepository;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.UserRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GMP 自查的自动核对：系统里有数据的检查项目，按自查期间核对一遍，给出“没发现问题 / 要注意 / 仅供参考”和例子。
 * 只读，不替代人工判定：制度是否建立、是否执行到位仍由检查人判断。都是批量查询，不在循环里查库。
 */
@Service
@RequiredArgsConstructor
public class GmpEvidenceService {

    /** 每项最多列几个例子。 */
    static final int MAX_DETAILS = 10;

    /** 投诉超过多少天没关算久拖。 */
    static final int COMPLAINT_OPEN_DAYS = 30;

    private final MasterLookup lookup;
    private final MfgOrderRepository mfgOrders;
    private final SupplierAuditRepository audits;
    private final UserRepository users;
    private final ControlledDocRepository docs;
    private final com.cosmetic.scm.plm.DocRevisionRepository revisions;
    private final QsoAppointmentRepository qsoAppointments;
    private final QsoDelegationRepository qsoDelegations;
    private final QsoDelegationReviewRepository qsoReviews;
    private final QualityDecisionRepository decisions;
    private final BatchRepository batches;
    private final RetainSampleRepository retains;
    private final com.cosmetic.scm.sales.SalesRecordService salesRecordService;
    private final StockRepository stocks;
    private final ComplaintRepository complaints;
    private final RecallRepository recalls;
    private final SalesReturnRepository returns;
    private final AuditRepository auditLogs;
    private final ProductFilingRepository filings;
    private final com.cosmetic.scm.oemcontract.OemContractRepository oemContracts;
    private final com.cosmetic.scm.oemcontract.OemContractProductRepository oemContractProducts;
    private final com.cosmetic.scm.master.BomRepository boms;
    private final com.cosmetic.scm.master.BomLineRepository bomLines;
    private final com.cosmetic.scm.oemsupervision.OemSupervisionRepository oemSupervisions;
    private final com.cosmetic.scm.oemsupervision.OemSupervisionFindingRepository oemFindings;
    private final CapaRepository capas;
    private final TimeProvider time;

    /** level：OK 没发现问题，WARN 要注意，INFO 仅供参考。 */
    public record Evidence(int itemNo, String level, String summary, List<String> details) {
    }

    /** orders：期间内新建、完工或现在还没完成的委托单（不含自产）。 */
    private record Ctx(LocalDate from, LocalDate to, LocalDateTime start, LocalDateTime end, LocalDate today, Map<Long, Item> items, Map<Long, Partner> partners,
                       List<MfgOrder> orders, List<QualityDecision> decisionsInPeriod) {

        boolean in(LocalDateTime t) {
            return t != null && !t.isBefore(start) && t.isBefore(end);
        }

        boolean in(LocalDate d) {
            return d != null && !d.isBefore(from) && !d.isAfter(to);
        }
    }

    @Transactional(readOnly = true)
    public List<Evidence> evidence(LocalDate from, LocalDate to) {
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();
        Map<Long, MfgOrder> oem = new java.util.LinkedHashMap<>();
        mfgOrders.findByMoTypeAndStatusIn(MoType.OEM, List.of(MoStatus.PLANNED, MoStatus.ISSUED)).forEach(m -> oem.put(m.getId(), m));
        mfgOrders.findByMoTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(MoType.OEM, start, end).forEach(m -> oem.put(m.getId(), m));
        mfgOrders.findByMoTypeAndCompletedAtGreaterThanEqualAndCompletedAtLessThan(MoType.OEM, start, end).forEach(m -> oem.put(m.getId(), m));
        Ctx c = new Ctx(from, to, start, end, time.today(), lookup.itemMap(), lookup.partnerMap(), List.copyOf(oem.values()),
                decisions.findBySignedAtGreaterThanEqual(start).stream().filter(d -> d.getSignedAt().isBefore(end)).toList());
        List<Evidence> out = new ArrayList<>();
        out.add(qualification(c));
        out.add(qso());
        out.add(qsoDelegation());
        out.add(oemAudit(c));
        out.add(oemList(c));
        out.add(contracts(c));
        Supervisions sv = supervisions(c);
        out.add(oemSupervision(c, sv));
        out.add(oemDisqualified(c, sv));
        out.add(oemRecords(c, sv));
        out.add(procedures());
        out.add(suppliedMaterials(c));
        List<Batch> released = releasedFgBatches(c);
        out.add(marketRelease(c, released));
        out.add(certificates(c));
        out.add(retainLocations(c));
        out.add(retainPerBatch(c, released));
        out.add(records(c));
        out.add(salesRecords(c));
        out.add(expiredStock(c));
        out.add(salesReturns(c));
        List<Complaint> cs = complaints.findByReceivedDateGreaterThanEqual(from).stream().filter(x -> !x.getReceivedDate().isAfter(to)).toList();
        out.add(complaintsCheck(c, cs));
        out.add(adverse(cs));
        out.add(recallCheck(c));
        out = withProcedures(out, c.today());
        out.sort(Comparator.comparingInt(Evidence::itemNo));
        return out;
    }

    /**
     * 把制度覆盖并进各项：列出对应制度已建立（文件编号）还是缺，缺必备制度的项目标为要注意；
     * 只有可选制度（提供物料时才要）缺的，提示即可。原来没有核对的项目（第 3、5、18 项）也加上制度核对。
     */
    private List<Evidence> withProcedures(List<Evidence> base, LocalDate today) {
        List<ControlledDoc> active = docs.findByStatus(DocStatus.ACTIVE).stream().filter(d -> d.getProcedures() != null).toList();
        Map<Long, List<com.cosmetic.scm.plm.DocRevision>> byDoc = active.isEmpty() ? Map.of()
                : revisions.findByDocIdIn(active.stream().map(ControlledDoc::getId).toList()).stream()
                        .collect(Collectors.groupingBy(com.cosmetic.scm.plm.DocRevision::getDocId));
        List<com.cosmetic.scm.plm.ProcedureCoverage.Row> rows = com.cosmetic.scm.plm.ProcedureCoverage.compute(active, byDoc, today);
        Map<Integer, Evidence> byNo = new java.util.TreeMap<>();
        base.forEach(e -> byNo.put(e.itemNo(), e));
        Map<Integer, List<com.cosmetic.scm.plm.ProcedureCoverage.Row>> byItem = new java.util.TreeMap<>();
        rows.forEach(r -> r.items().forEach(no -> byItem.computeIfAbsent(no, k -> new ArrayList<>()).add(r)));
        byItem.forEach((no, list) -> {
            List<String> lines = new ArrayList<>();
            for (com.cosmetic.scm.plm.ProcedureCoverage.Row r : list) {
                switch (r.state()) {
                    case ESTABLISHED -> lines.add("制度“" + r.label() + "”已建立：" + r.docs().stream().map(x -> x.docCode() + " 第 " + x.currentRev() + " 版")
                            .collect(Collectors.joining("、")));
                    case DRAFTING -> lines.add("制度“" + r.label() + "”的文件还没生效" + (r.optional() ? "（提供物料时才要）" : ""));
                    default -> lines.add("制度“" + r.label() + "”还没有对应的受控文件" + (r.optional() ? "（提供物料时才要）" : ""));
                }
            }
            long missing = list.stream().filter(r -> !r.optional() && r.state() != com.cosmetic.scm.plm.ProcedureCoverage.State.ESTABLISHED).count();
            Evidence old = byNo.get(no);
            String note = missing > 0 ? "缺 " + missing + " 项制度的生效文件。" : "对应的制度都有生效文件。";
            if (old == null) {
                byNo.put(no, new Evidence(no, missing > 0 ? "WARN" : "OK", note + "制度是否执行到位请人工核对。", lines));
            } else {
                List<String> details = new ArrayList<>(old.details());
                details.addAll(lines);
                byNo.put(no, new Evidence(no, missing > 0 ? "WARN" : old.level(), old.summary() + (missing > 0 ? " " + note : ""), details));
            }
        });
        return new ArrayList<>(byNo.values());
    }

    // ---- 委托资格和受托生产企业 ----

    private List<Partner> oemFactories(Ctx c) {
        return c.partners().values().stream().filter(p -> p.getKind() == PartnerKind.OEM).sorted(Comparator.comparing(Partner::getCode)).toList();
    }

    /** 期间内下过或在做委托单的受托厂。 */
    private Set<Long> activeFactories(Ctx c) {
        return c.orders().stream().filter(m -> m.getMoType() == MoType.OEM && m.getStatus() != MoStatus.CANCELLED
                        && (c.in(m.getCreatedAt()) || c.in(m.getCompletedAt()) || m.getStatus() == MoStatus.PLANNED || m.getStatus() == MoStatus.ISSUED))
                .map(MfgOrder::getFactoryId).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private Evidence qualification(Ctx c) {
        List<String> problems = new ArrayList<>();
        for (Long id : activeFactories(c)) {
            Partner p = c.partners().get(id);
            if (p == null) {
                continue;
            }
            if (p.getLicenseNo() == null || p.getLicenseNo().isBlank()) {
                problems.add(p.getName() + "：没有登记生产许可证编号");
            } else if (p.licenseExpired(c.today())) {
                problems.add(p.getName() + "：生产许可证 " + p.getLicenseNo() + " 已于 " + p.getLicenseExpiry() + " 过期");
            }
        }
        // 期间内委托生产的成品要有已完成的备案或注册（委托方是注册人或备案人）
        Set<Long> filed = filings.findByStatus(FilingStatus.FILED).stream().map(ProductFiling::getFgItemId).collect(Collectors.toSet());
        c.orders().stream().filter(m -> m.getMoType() == MoType.OEM && m.getStatus() != MoStatus.CANCELLED && (c.in(m.getCreatedAt()) || c.in(m.getCompletedAt())))
                .map(MfgOrder::getFgItemId).distinct().filter(fg -> !filed.contains(fg))
                .forEach(fg -> problems.add(name(c, fg) + "：委托生产了，但系统里没有已完成的备案或注册"));
        int factories = activeFactories(c).size();
        if (problems.isEmpty()) {
            return new Evidence(2, "OK", "期间内 " + factories + " 家受托厂的生产许可证都已登记且在有效期内，委托生产的成品都有备案或注册。许可范围（眼部、儿童护肤类）请人工核对。", List.of());
        }
        return new Evidence(2, "WARN", problems.size() + " 处要注意。许可范围（眼部、儿童护肤类）请人工核对。", limit(problems));
    }

    /** 第 4 项：现任质量安全负责人的任职档案；有质量安全负责人角色的账号要么是现任、要么有生效授权（第 5 项另列授权）。 */
    private Evidence qso() {
        var cur = qsoAppointments.findFirstByStatus(com.cosmetic.scm.domain.AppointmentStatus.ACTIVE).orElse(null);
        List<String> details = new ArrayList<>();
        List<String> problems = unauthorizedQso(cur);
        if (cur == null) {
            details.addAll(problems);
            return new Evidence(4, "WARN", "还没有登记生效的质量安全负责人任职档案（专业、经验年限、任命文件）。", limit(details));
        }
        details.add(cur.getPersonName() + "（" + cur.getUsername() + "）：" + cur.getMajorField().getLabel()
                + (cur.getMajorDetail() == null ? "" : " " + cur.getMajorDetail()) + "，经验 " + cur.getYearsExperience() + " 年，" + cur.getAppointedOn() + " 任职");
        details.addAll(problems);
        return new Evidence(4, problems.isEmpty() ? "OK" : "WARN", problems.isEmpty() ? "现任质量安全负责人有任职档案，有质量安全负责人角色的账号都是现任或有生效授权。履职情况请人工核对。"
                : problems.size() + " 个账号有质量安全负责人角色，却不是现任也没有生效授权。", limit(details));
    }

    /** 第 5 项：协助履职授权和监督记录。 */
    private Evidence qsoDelegation() {
        LocalDate today = time.today();
        var cur = qsoAppointments.findFirstByStatus(com.cosmetic.scm.domain.AppointmentStatus.ACTIVE).orElse(null);
        List<QsoDelegation> active = qsoDelegations.findByStatus(com.cosmetic.scm.domain.DelegationStatus.ACTIVE).stream()
                .filter(d -> !today.isBefore(d.getValidFrom()) && !today.isAfter(d.getValidTo())).toList();
        if (active.isEmpty()) {
            return new Evidence(5, cur == null ? "WARN" : "OK", cur == null ? "还没有现任质量安全负责人。" : "没有生效中的协助履职授权，质量安全负责人独立履职。", unauthorizedQso(cur));
        }
        Map<Long, LocalDate> last = new HashMap<>();
        reviewsOf(active).forEach(r -> last.merge(r.getDelegationId(), r.getReviewDate(), (a, b) -> a.isAfter(b) ? a : b));
        List<String> details = new ArrayList<>();
        int due = 0;
        for (QsoDelegation d : active) {
            LocalDate since = last.getOrDefault(d.getId(), d.getValidFrom());
            boolean late = since.plusMonths(QsoService.REVIEW_MONTHS).isBefore(today);
            due += late ? 1 : 0;
            details.add(d.getDelegationNo() + " " + d.getDelegateUsername() + "，" + d.getValidFrom() + " 至 " + d.getValidTo()
                    + (last.containsKey(d.getId()) ? "，最近监督 " + last.get(d.getId()) : "，还没有监督记录") + (late ? "（超过 " + QsoService.REVIEW_MONTHS + " 个月没有监督）" : ""));
        }
        details.addAll(unauthorizedQso(cur));
        boolean warn = due > 0 || !unauthorizedQso(cur).isEmpty();
        return new Evidence(5, warn ? "WARN" : "OK", "生效中的协助履职授权 " + active.size() + " 份" + (due > 0 ? "，" + due + " 份超过 " + QsoService.REVIEW_MONTHS + " 个月没有监督记录" : "，都有近期监督记录")
                + "。", limit(details));
    }

    private List<QsoDelegationReview> reviewsOf(List<QsoDelegation> list) {
        return qsoReviews.findByDelegationIdInOrderByReviewDateAscIdAsc(list.stream().map(QsoDelegation::getId).toList());
    }

    /** 有质量安全负责人角色、却不是现任也没有生效授权的账号。 */
    private List<String> unauthorizedQso(QsoAppointment cur) {
        LocalDate today = time.today();
        java.util.Set<String> delegated = qsoDelegations.findByStatus(com.cosmetic.scm.domain.DelegationStatus.ACTIVE).stream()
                .filter(d -> !today.isBefore(d.getValidFrom()) && !today.isAfter(d.getValidTo())).map(QsoDelegation::getDelegateUsername).collect(Collectors.toSet());
        return users.findAll().stream().filter(u -> u.isEnabled() && !u.isPortal() && u.hasRole(Role.QSO))
                .filter(u -> (cur == null || !cur.getUsername().equals(u.getUsername())) && !delegated.contains(u.getUsername()))
                .map(u -> (u.getDisplayName() == null ? u.getUsername() : u.getDisplayName() + "（" + u.getUsername() + "）") + "：有质量安全负责人角色，但不是现任，也没有生效授权")
                .sorted().toList();
    }

    private Evidence oemAudit(Ctx c) {
        Set<Long> active = activeFactories(c);
        Map<Long, List<SupplierAudit>> byPartner = active.isEmpty() ? Map.of()
                : audits.findByPartnerIdIn(active).stream().collect(Collectors.groupingBy(SupplierAudit::getPartnerId));
        List<String> problems = new ArrayList<>();
        for (Long id : active) {
            Partner p = c.partners().get(id);
            List<SupplierAudit> list = byPartner.getOrDefault(id, List.of()).stream()
                    .sorted(Comparator.comparing(SupplierAudit::getAuditDate).reversed()).toList();
            if (list.isEmpty()) {
                problems.add(p.getName() + "：没有审核记录");
            } else if (list.get(0).getResult() == AuditResult.FAIL) {
                problems.add(p.getName() + "：最近一次审核（" + list.get(0).getAuditDate() + "）不合格");
            } else if (list.get(0).getNextAuditDate() != null && list.get(0).getNextAuditDate().isBefore(c.today())) {
                problems.add(p.getName() + "：下次审核日期 " + list.get(0).getNextAuditDate() + " 已过");
            }
        }
        return problems.isEmpty()
                ? new Evidence(6, "OK", "期间内委托生产的受托厂都有审核记录，最近一次合格且没过下次审核日期。", List.of())
                : new Evidence(6, "WARN", problems.size() + " 家受托厂的审核要注意。", limit(problems));
    }

    private Evidence oemList(Ctx c) {
        List<Partner> all = oemFactories(c);
        List<com.cosmetic.scm.domain.rules.ContractCoverage.Term> terms = com.cosmetic.scm.oemcontract.OemContractTerms.ofFactories(oemContracts, oemContractProducts,
                all.stream().map(Partner::getId).toList());
        List<String> details = all.stream().map(p -> p.getName() + "（" + p.getCode() + "）许可证 " + (p.getLicenseNo() == null ? "未登记" : p.getLicenseNo())
                + (p.getLicenseExpiry() == null ? "" : "，有效期至 " + p.getLicenseExpiry()) + (p.isQualified() ? "" : "，已停用") + contractsOf(terms, p.getId(), c.today())).toList();
        long missing = all.stream().filter(p -> p.getLicenseNo() == null || p.getLicenseNo().isBlank()).count();
        return new Evidence(7, missing > 0 ? "WARN" : "INFO", "主数据里有受托生产企业 " + all.size() + " 家" + (missing > 0 ? "，其中 " + missing + " 家没有登记许可证编号" : "")
                + "；委托合同见第 8 项，审核评估见第 6 项，其他资料请在往来单位的附件里核对。", limit(details));
    }

    private static String contractsOf(List<com.cosmetic.scm.domain.rules.ContractCoverage.Term> terms, Long factoryId, LocalDate today) {
        List<String> nos = terms.stream().filter(t -> t.factoryId().equals(factoryId) && t.inForce(today))
                .map(t -> t.contractNo() + "（至 " + t.validTo() + "）").toList();
        return nos.isEmpty() ? "，没有执行中的委托生产合同" : "，执行中的合同 " + String.join("、", nos);
    }

    /**
     * 第 8 项：期间内下的和还没完成的委托单，下单时有没有覆盖这个成品的有效委托生产合同；执行中的合同约定的物料采购分工和现行 BOM 是否一致。
     * 合同条款是否履行仍要人工核对。
     */
    private Evidence contracts(Ctx c) {
        List<MfgOrder> list = c.orders().stream().filter(m -> m.getMoType() == MoType.OEM && m.getStatus() != MoStatus.CANCELLED
                && (c.in(m.getCreatedAt()) || m.getStatus() == MoStatus.PLANNED || m.getStatus() == MoStatus.ISSUED)).toList();
        Set<Long> factories = new java.util.HashSet<>(activeFactories(c));
        list.stream().map(MfgOrder::getFactoryId).filter(Objects::nonNull).forEach(factories::add);
        List<com.cosmetic.scm.oemcontract.OemContract> signed = factories.isEmpty() ? List.of()
                : oemContracts.findByFactoryIdInAndStatusIn(factories, com.cosmetic.scm.oemcontract.OemContractTerms.APPROVED);
        List<com.cosmetic.scm.domain.rules.ContractCoverage.Term> terms = com.cosmetic.scm.oemcontract.OemContractTerms.terms(signed, oemContractProducts);
        List<String> problems = new ArrayList<>();
        for (MfgOrder m : list) {
            if (com.cosmetic.scm.domain.rules.ContractCoverage.covering(terms, m.getFactoryId(), m.getFgItemId(), m.getCreatedAt()).isEmpty()) {
                Partner f = c.partners().get(m.getFactoryId());
                problems.add(m.getMoNo() + "：" + name(c, m.getFgItemId()) + "（" + (f == null ? "" : f.getName()) + "）" + m.getCreatedAt().toLocalDate()
                        + " 下单时没有覆盖这个成品的有效委托生产合同");
            }
        }
        Map<Long, com.cosmetic.scm.domain.rules.ContractCoverage.Term> termById = terms.stream()
                .collect(Collectors.toMap(com.cosmetic.scm.domain.rules.ContractCoverage.Term::id, t -> t));
        List<com.cosmetic.scm.oemcontract.OemContract> inForce = signed.stream().filter(x -> termById.get(x.getId()).inForce(c.today()))
                .sorted(Comparator.comparing(com.cosmetic.scm.oemcontract.OemContract::getContractNo)).toList();
        Map<Long, List<com.cosmetic.scm.domain.rules.ContractCoverage.Line>> bom = com.cosmetic.scm.oemcontract.OemContractTerms.bomLines(boms, bomLines,
                inForce.stream().flatMap(x -> termById.get(x.getId()).products().stream()).collect(Collectors.toSet()), c.items());
        List<String> lines = new ArrayList<>();
        for (com.cosmetic.scm.oemcontract.OemContract x : inForce) {
            Set<Long> products = termById.get(x.getId()).products();
            for (Long fg : products) {
                com.cosmetic.scm.domain.rules.ContractCoverage.supplyMismatches(name(c, fg), x.getRmSupply(), x.getPkSupply(), bom.getOrDefault(fg, List.of()))
                        .forEach(s -> problems.add(x.getContractNo() + " " + s));
            }
            Partner f = c.partners().get(x.getFactoryId());
            lines.add("执行中：" + x.getContractNo() + " " + (f == null ? "" : f.getName()) + "，" + x.getValidFrom() + " 至 " + x.getValidTo() + "，" + products.size() + " 个成品");
        }
        List<String> details = new ArrayList<>(problems);
        details.addAll(lines);
        if (!problems.isEmpty()) {
            return new Evidence(8, "WARN", problems.size() + " 处要注意。合同条款是否履行请人工核对。", limit(details));
        }
        if (list.isEmpty()) {
            return new Evidence(8, "INFO", "期间内没有委托单。" + (inForce.isEmpty() ? "" : "执行中的委托生产合同 " + inForce.size() + " 份。"), limit(details));
        }
        return new Evidence(8, "OK", "期间内 " + list.size() + " 张委托单下单时都有覆盖该成品的有效委托生产合同"
                + (inForce.isEmpty() ? "。" : "，执行中合同约定的物料采购分工和现行 BOM 一致。") + "合同条款是否履行请人工核对。", limit(details));
    }

    /** 期间内（按监督日期）提交了或审核过的受托生产监督，和它们发现的问题、转出的 CAPA。 */
    private record Supervisions(List<com.cosmetic.scm.oemsupervision.OemSupervision> list,
                                Map<Long, List<com.cosmetic.scm.oemsupervision.OemSupervisionFinding>> findings, Map<Long, Capa> capas) {

        List<com.cosmetic.scm.oemsupervision.OemSupervisionFinding> of(Long id) {
            return findings.getOrDefault(id, List.of());
        }

        /** 转了 CAPA、CAPA 还没验证的问题。 */
        boolean capaOpen(com.cosmetic.scm.oemsupervision.OemSupervisionFinding f) {
            Capa x = f.getCapaId() == null ? null : capas.get(f.getCapaId());
            return x != null && x.getStatus() != com.cosmetic.scm.domain.CapaStatus.VERIFIED;
        }
    }

    private Supervisions supervisions(Ctx c) {
        List<com.cosmetic.scm.oemsupervision.OemSupervision> list = oemSupervisions.findBySupervisedOnBetweenAndStatusInOrderBySupervisedOnAscIdAsc(c.from(), c.to(),
                com.cosmetic.scm.oemsupervision.OemSupervisionService.EFFECTIVE);
        if (list.isEmpty()) {
            return new Supervisions(List.of(), Map.of(), Map.of());
        }
        Map<Long, List<com.cosmetic.scm.oemsupervision.OemSupervisionFinding>> byId = oemFindings.findBySupervisionIdInOrderBySupervisionIdAscSeqAsc(
                list.stream().map(com.cosmetic.scm.oemsupervision.OemSupervision::getId).toList()).stream()
                .collect(Collectors.groupingBy(com.cosmetic.scm.oemsupervision.OemSupervisionFinding::getSupervisionId));
        Map<Long, Capa> capaMap = capas.findAllById(byId.values().stream().flatMap(List::stream).map(com.cosmetic.scm.oemsupervision.OemSupervisionFinding::getCapaId)
                .filter(Objects::nonNull).distinct().toList()).stream().collect(Collectors.toMap(Capa::getId, Function.identity()));
        return new Supervisions(list, byId, capaMap);
    }

    private static String svLine(Ctx c, com.cosmetic.scm.oemsupervision.OemSupervision s, Map<Long, MfgOrder> mos) {
        Partner f = c.partners().get(s.getFactoryId());
        MfgOrder m = s.getMoId() == null ? null : mos.get(s.getMoId());
        return s.getSupervisionNo() + " " + s.getSupervisedOn() + " " + (f == null ? "" : f.getName()) + " " + s.getMethod().getLabel()
                + (m == null ? "" : "（委托单 " + m.getMoNo() + "）") + (s.getStatus() == com.cosmetic.scm.domain.SupervisionStatus.SUBMITTED ? "，待审核" : "");
    }

    /**
     * 第 9 项：期间内有委托生产的受托厂都要有监督记录（驻厂、现场检查、调阅记录、远程视频），提交了的都要审核；
     * 列出发现的问题和还没验证的 CAPA，以及完工的委托单里有多少张有针对本单的监督。
     */
    private Evidence oemSupervision(Ctx c, Supervisions sv) {
        long completed = c.orders().stream().filter(m -> m.getMoType() == MoType.OEM && m.getStatus() == MoStatus.COMPLETED && c.in(m.getCompletedAt())).count();
        Map<Long, Batch> byId = batchesOf(c.decisionsInPeriod());
        long oemReleases = c.decisionsInPeriod().stream().filter(d -> d.getStage() == QcStage.MARKET_RELEASE && d.getDecision() == Decision.RELEASED)
                .map(d -> byId.get(d.getBatchId())).filter(b -> b != null && b.isOemReleased()).count();
        Set<Long> active = activeFactories(c);
        Set<Long> supervised = sv.list().stream().map(com.cosmetic.scm.oemsupervision.OemSupervision::getFactoryId).collect(Collectors.toSet());
        Set<Long> coveredMo = sv.list().stream().map(com.cosmetic.scm.oemsupervision.OemSupervision::getMoId).filter(Objects::nonNull).collect(Collectors.toSet());
        long completedCovered = c.orders().stream().filter(m -> m.getMoType() == MoType.OEM && m.getStatus() == MoStatus.COMPLETED && c.in(m.getCompletedAt())
                && coveredMo.contains(m.getId())).count();
        List<String> problems = new ArrayList<>();
        active.stream().filter(id -> !supervised.contains(id)).map(id -> c.partners().get(id)).filter(Objects::nonNull)
                .sorted(Comparator.comparing(Partner::getCode)).forEach(p -> problems.add(p.getName() + "：期间内有委托生产，没有监督记录"));
        long pending = sv.list().stream().filter(s -> s.getStatus() == com.cosmetic.scm.domain.SupervisionStatus.SUBMITTED).count();
        if (pending > 0) {
            problems.add(pending + " 份监督记录还没有质量安全负责人审核");
        }
        Map<Long, MfgOrder> mos = new HashMap<>();
        c.orders().forEach(m -> mos.put(m.getId(), m));
        List<String> details = new ArrayList<>(problems);
        Map<com.cosmetic.scm.domain.SupervisionMethod, Long> byMethod = sv.list().stream()
                .collect(Collectors.groupingBy(com.cosmetic.scm.oemsupervision.OemSupervision::getMethod, () -> new java.util.EnumMap<>(com.cosmetic.scm.domain.SupervisionMethod.class),
                        Collectors.counting()));
        int findingCount = 0;
        int major = 0;
        for (com.cosmetic.scm.oemsupervision.OemSupervision s : sv.list()) {
            for (com.cosmetic.scm.oemsupervision.OemSupervisionFinding f : sv.of(s.getId())) {
                findingCount++;
                major += f.getSeverity() == com.cosmetic.scm.domain.FindingSeverity.MAJOR ? 1 : 0;
                if (sv.capaOpen(f)) {
                    Capa x = sv.capas().get(f.getCapaId());
                    details.add(s.getSupervisionNo() + " 问题 " + f.getSeq() + "（" + f.getSeverity().getLabel() + "）：CAPA " + x.getCapaNo() + " " + x.getStatus().getLabel());
                }
            }
        }
        sv.list().stream().sorted(Comparator.comparing(com.cosmetic.scm.oemsupervision.OemSupervision::getSupervisedOn).reversed()).limit(10)
                .forEach(s -> details.add(svLine(c, s, mos)));
        String counts = sv.list().isEmpty() ? "" : "共 " + sv.list().size() + " 次（" + byMethod.entrySet().stream().map(e -> e.getKey().getLabel() + " " + e.getValue())
                .collect(Collectors.joining("、")) + "），发现问题 " + findingCount + " 个" + (major > 0 ? "（重大 " + major + " 个）" : "") + "，都已当场纠正或转 CAPA";
        String tail = "期间内委托生产完工 " + completed + " 单，其中 " + completedCovered + " 单有针对本单的监督；上市放行时审核过受托厂出厂放行记录的 " + oemReleases + " 批。";
        if (active.isEmpty() && sv.list().isEmpty()) {
            return new Evidence(9, "INFO", "期间内没有委托生产，也没有监督记录。", List.of());
        }
        if (!problems.isEmpty()) {
            String head = problems.size() <= 3 ? String.join("；", problems) + "。" : problems.size() + " 处要注意。";
            return new Evidence(9, "WARN", head + (counts.isEmpty() ? "" : "期间内监督" + counts + "。") + tail, limit(details));
        }
        return new Evidence(9, "OK", "期间内委托生产的 " + active.size() + " 家受托厂都有监督记录，都已审核：" + counts + "。" + tail, limit(details));
    }

    /**
     * 第 10 项：已停用的受托厂不能还有没完成的委托单；期间内监督发现重大问题、CAPA 还没验证、又还有在做的委托单的受托厂，要评估是否停止委托。
     */
    private Evidence oemDisqualified(Ctx c, Supervisions sv) {
        List<MfgOrder> open = c.orders().stream().filter(m -> m.getMoType() == MoType.OEM && (m.getStatus() == MoStatus.PLANNED || m.getStatus() == MoStatus.ISSUED)).toList();
        List<String> problems = new ArrayList<>(open.stream()
                .filter(m -> {
                    Partner p = c.partners().get(m.getFactoryId());
                    return p != null && !p.isQualified();
                }).map(m -> m.getMoNo() + "：受托厂 " + c.partners().get(m.getFactoryId()).getName() + " 已停用，委托单还没完成").toList());
        Set<Long> withOpen = open.stream().map(MfgOrder::getFactoryId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, List<String>> risky = new java.util.TreeMap<>();
        for (com.cosmetic.scm.oemsupervision.OemSupervision s : sv.list()) {
            for (com.cosmetic.scm.oemsupervision.OemSupervisionFinding f : sv.of(s.getId())) {
                if (f.getSeverity() == com.cosmetic.scm.domain.FindingSeverity.MAJOR && sv.capaOpen(f) && withOpen.contains(s.getFactoryId())) {
                    risky.computeIfAbsent(s.getFactoryId(), k -> new ArrayList<>()).add(s.getSupervisionNo() + " 问题 " + f.getSeq());
                }
            }
        }
        risky.forEach((id, list) -> {
            Partner p = c.partners().get(id);
            problems.add((p == null ? String.valueOf(id) : p.getName()) + "：监督发现的重大问题（" + String.join("、", list)
                    + "）整改还没验证，还有在做的委托单，请评估是否需要停止委托");
        });
        return problems.isEmpty()
                ? new Evidence(10, "OK", "没有已停用的受托厂还有未完成的委托单；在做委托单的受托厂没有未验证整改的重大监督问题。", List.of())
                : new Evidence(10, "WARN", problems.size() + " 处要注意。", limit(problems));
    }

    /** 第 18 项：期间内调阅或检查受托方生产质量记录的监督（方式“调阅记录”或环节含“生产质量记录”）。 */
    private Evidence oemRecords(Ctx c, Supervisions sv) {
        List<com.cosmetic.scm.oemsupervision.OemSupervision> reviews = sv.list().stream()
                .filter(s -> s.getMethod() == com.cosmetic.scm.domain.SupervisionMethod.RECORD_REVIEW
                        || (s.getScopes() != null && List.of(s.getScopes().split(",")).contains(com.cosmetic.scm.domain.SupervisionScope.RECORDS.name())))
                .sorted(Comparator.comparing(com.cosmetic.scm.oemsupervision.OemSupervision::getSupervisedOn).reversed()).toList();
        Map<Long, MfgOrder> mos = new HashMap<>();
        c.orders().forEach(m -> mos.put(m.getId(), m));
        List<String> details = reviews.stream().map(s -> svLine(c, s, mos)).toList();
        if (activeFactories(c).isEmpty() && reviews.isEmpty()) {
            return new Evidence(18, "INFO", "期间内没有委托生产。委托方提供物料的，相关记录的保存安排请对照委托生产合同核对。", List.of());
        }
        if (reviews.isEmpty()) {
            return new Evidence(18, "WARN", "期间内有委托生产，但没有调阅或检查受托方生产质量记录的监督记录（在“受托生产监督”里登记方式“调阅记录”，或环节勾“生产质量记录”）。",
                    List.of());
        }
        return new Evidence(18, "OK", "期间内调阅或检查受托方生产质量记录 " + reviews.size() + " 次。委托方提供物料的，相关记录的保存安排请对照委托生产合同核对。", limit(details));
    }

    // ---- 制度和物料 ----

    private Evidence procedures() {
        List<ControlledDoc> active = docs.findByStatus(DocStatus.ACTIVE);
        Map<String, Long> byCat = active.stream().collect(Collectors.groupingBy(d -> d.getCategory().getLabel(), Collectors.counting()));
        List<String> details = byCat.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e -> e.getKey() + " " + e.getValue() + " 份").toList();
        return new Evidence(11, active.isEmpty() ? "WARN" : "INFO", active.isEmpty() ? "系统里还没有在用的受控文件，制度都还没有登记。"
                : "在用受控文件 " + active.size() + " 份；各项制度（注册备案、健康、培训、自查、放行、留样、销售记录、贮存运输、退货、投诉、召回、不良反应）是否齐全请人工核对。", details);
    }

    private Evidence suppliedMaterials(Ctx c) {
        Map<Long, Batch> byId = batchesOf(c.decisionsInPeriod());
        List<QualityDecision> material = c.decisionsInPeriod().stream().filter(d -> d.getStage() == QcStage.MATERIAL_QC).toList();
        long released = material.stream().filter(d -> d.getDecision() == Decision.RELEASED).count();
        long rejected = material.size() - released;
        long rm = material.stream().map(d -> byId.get(d.getBatchId())).filter(Objects::nonNull)
                .map(b -> c.items().get(b.getItemId())).filter(i -> i != null && i.getItemType() == ItemType.RM).count();
        return new Evidence(12, "INFO", "期间内物料检验放行 " + released + " 批、拒收 " + rejected + " 批（其中原料 " + rm + " 批）。不向受托方提供物料的可判不适用。", List.of());
    }

    // ---- 放行、合格证明、留样 ----

    /** 期间内上市放行的成品批次（按放行签批时间）。 */
    private List<Batch> releasedFgBatches(Ctx c) {
        Set<Long> ids = c.decisionsInPeriod().stream().filter(d -> d.getStage() == QcStage.MARKET_RELEASE && d.getDecision() == Decision.RELEASED)
                .map(QualityDecision::getBatchId).collect(Collectors.toSet());
        return batches.findAllById(ids).stream().sorted(Comparator.comparing(Batch::getBatchNo)).toList();
    }

    private Evidence marketRelease(Ctx c, List<Batch> released) {
        List<String> problems = new ArrayList<>();
        // 期间内生产或委托做出来、现在已放行的成品，都要有上市放行签批（期初导入的不算）
        List<Batch> fgOut = batches.findBySourceTypeAndStatusAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(SourceType.MO, BatchStatus.RELEASED, c.start(), c.end())
                .stream().filter(b -> isFg(c, b)).toList();
        Set<Long> signed = fgOut.isEmpty() ? Set.of() : decisions.findByBatchIdIn(fgOut.stream().map(Batch::getId).toList()).stream()
                .filter(d -> d.getStage() == QcStage.MARKET_RELEASE && d.getDecision() == Decision.RELEASED).map(QualityDecision::getBatchId).collect(Collectors.toSet());
        fgOut.stream().filter(b -> !signed.contains(b.getId())).forEach(b -> problems.add(b.getBatchNo() + "：已放行，但没有上市放行签批记录"));
        // 委托生产的批次，放行时要有受托厂的出厂放行
        Set<String> moNos = released.stream().filter(b -> b.getSourceType() == SourceType.MO).map(Batch::getSourceNo).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> oemMoNos = moNos.isEmpty() ? Set.of() : mfgOrders.findByMoNoIn(moNos).stream().filter(m -> m.getMoType() == MoType.OEM)
                .map(MfgOrder::getMoNo).collect(Collectors.toSet());
        released.stream().filter(b -> b.getSourceType() == SourceType.MO && oemMoNos.contains(b.getSourceNo()) && !b.isOemReleased())
                .forEach(b -> problems.add(b.getBatchNo() + "：委托生产，上市放行时没有受托厂的出厂放行"));
        Set<String> signers = c.decisionsInPeriod().stream().filter(d -> d.getStage() == QcStage.MARKET_RELEASE && d.getDecision() == Decision.RELEASED)
                .map(QualityDecision::getSignedByName).filter(Objects::nonNull).collect(Collectors.toCollection(java.util.TreeSet::new));
        if (problems.isEmpty()) {
            return new Evidence(13, "OK", "期间内上市放行 " + released.size() + " 批，都有质量安全负责人签批" + (signers.isEmpty() ? "" : "（" + String.join("、", signers) + "）")
                    + "，委托生产的都有受托厂出厂放行。", List.of());
        }
        return new Evidence(13, "WARN", problems.size() + " 批放行要注意。", limit(problems));
    }

    private Evidence certificates(Ctx c) {
        List<String> problems = c.decisionsInPeriod().stream().filter(d -> d.getStage() == QcStage.MARKET_RELEASE && d.getDecision() == Decision.RELEASED)
                .filter(d -> d.getReportNo() == null || d.getReportNo().isBlank()).map(d -> "放行记录 " + d.getId() + "：没有出厂检验报告编号").toList();
        long n = c.decisionsInPeriod().stream().filter(d -> d.getStage() == QcStage.MARKET_RELEASE && d.getDecision() == Decision.RELEASED).count();
        return problems.isEmpty()
                ? new Evidence(14, "OK", "期间内上市放行的 " + n + " 批都登记了出厂检验报告编号。标签是否符合规定请对照“标签设计稿”核对。", List.of())
                : new Evidence(14, "WARN", problems.size() + " 批没有出厂检验报告编号。", limit(problems));
    }

    private Evidence retainLocations(Ctx c) {
        List<String> locations = retains.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(c.start(), c.end()).stream().map(RetainSample::getLocation)
                .filter(Objects::nonNull).distinct().sorted().toList();
        return new Evidence(15, "INFO", locations.isEmpty() ? "期间内没有新的留样。" : "期间内留样用到 " + locations.size() + " 个地点；不在住所或主要经营场所的，请核对是否已按期报告。", limit(locations));
    }

    private Evidence retainPerBatch(Ctx c, List<Batch> released) {
        Set<Long> withSample = released.isEmpty() ? Set.of()
                : retains.findByBatchIdIn(released.stream().map(Batch::getId).toList()).stream().map(RetainSample::getBatchId).collect(Collectors.toSet());
        List<String> problems = released.stream().filter(b -> b.getSourceType() == SourceType.MO && !withSample.contains(b.getId()))
                .map(b -> b.getBatchNo() + "：上市放行了，没有留样记录").toList();
        return problems.isEmpty()
                ? new Evidence(16, "OK", "期间内上市放行的 " + released.size() + " 批都有留样记录，留样期限由系统按使用期限后 6 个月计算。留样区域和贮存条件请现场核对。", List.of())
                : new Evidence(16, "WARN", problems.size() + " 批没有留样记录。", limit(problems));
    }

    private Evidence records(Ctx c) {
        long logs = auditLogs.countByCreatedAtBetween(c.start(), c.end());
        long signed = c.decisionsInPeriod().size();
        return new Evidence(17, "INFO", "期间内审计日志 " + logs + " 条，检验放行和上市放行电子签批 " + signed + " 次；系统有账号权限、审计追踪、签批密码和自动备份。", List.of());
    }

    // ---- 销售、贮存、退货、投诉、不良反应、召回 ----

    private Evidence salesRecords(Ctx c) {
        var r = salesRecordService.records(c.from(), c.to());
        if (r.orders() == 0) {
            return new Evidence(19, "INFO", "期间内没有销售出库。", List.of());
        }
        if (r.incompleteRows() == 0) {
            return new Evidence(19, "OK", "期间内销售出库 " + r.orders() + " 单、" + r.rows().size() + " 行，产品名称、注册证或备案编号、限期使用日期、净含量、数量、"
                    + "销售日期、价格和购买者名称、地址、联系方式都有记录（出库时记下，见“销售出库 → 产品销售记录”）。", List.of());
        }
        List<String> details = new ArrayList<>(r.gapSummary());
        r.rows().stream().filter(x -> !x.complete()).limit(10)
                .forEach(x -> details.add(x.soNo() + " " + x.itemName() + " " + (x.batchNo() == null ? "" : x.batchNo() + " ") + "：" + String.join("、", x.gaps())));
        return new Evidence(19, "WARN", "期间内销售出库 " + r.orders() + " 单，" + r.incompleteRows() + " 行销售记录不全。", limit(details));
    }

    private Evidence expiredStock(Ctx c) {
        List<Stock> on = stocks.findByQtyGreaterThan(BigDecimal.ZERO);
        Map<Long, Batch> byId = batches.findAllById(on.stream().map(Stock::getBatchId).distinct().toList()).stream()
                .collect(Collectors.toMap(Batch::getId, Function.identity()));
        Map<Long, BigDecimal> qty = new HashMap<>();
        on.forEach(s -> qty.merge(s.getBatchId(), s.getQty(), BigDecimal::add));
        List<String> problems = byId.values().stream().filter(b -> isFg(c, b) && b.getExpDate() != null && b.getExpDate().isBefore(c.today())
                        && b.getStatus() != BatchStatus.REJECTED)
                .sorted(Comparator.comparing(Batch::getExpDate))
                .map(b -> b.getBatchNo() + "：已于 " + b.getExpDate() + " 过期，还有 " + qty.get(b.getId()).stripTrailingZeros().toPlainString() + " 在库").toList();
        return problems.isEmpty()
                ? new Evidence(20, "OK", "没有过期的成品还在库。贮存运输条件请现场核对。", List.of())
                : new Evidence(20, "WARN", problems.size() + " 批过期成品还在库，没有处理。", limit(problems));
    }

    private Evidence salesReturns(Ctx c) {
        long n = returns.findByReturnDateGreaterThanEqual(c.from()).stream().filter(r -> !r.getReturnDate().isAfter(c.to())).count();
        return new Evidence(21, "INFO", "期间内退货 " + n + " 单，退货单位、产品、批号、数量、原因和处置结果在“销售退货”里。", List.of());
    }

    private Evidence complaintsCheck(Ctx c, List<Complaint> cs) {
        List<String> late = cs.stream().filter(x -> x.getStatus() == CaseStatus.OPEN && x.getReceivedDate().plusDays(COMPLAINT_OPEN_DAYS).isBefore(c.today()))
                .map(x -> x.getComplaintNo() + "：" + x.getReceivedDate() + " 收到，超过 " + COMPLAINT_OPEN_DAYS + " 天还没关闭").toList();
        return late.isEmpty()
                ? new Evidence(22, "OK", "期间内投诉 " + cs.size() + " 件，没有超过 " + COMPLAINT_OPEN_DAYS + " 天还没处理完的。", List.of())
                : new Evidence(22, "WARN", "期间内投诉 " + cs.size() + " 件，" + late.size() + " 件超过 " + COMPLAINT_OPEN_DAYS + " 天还没关闭。", limit(late));
    }

    private Evidence adverse(List<Complaint> cs) {
        List<Complaint> ad = cs.stream().filter(x -> x.getCategory() == ComplaintCategory.ADVERSE).toList();
        List<String> pending = ad.stream().filter(x -> x.getReportedDate() == null).map(x -> x.getComplaintNo() + "：" + x.getReceivedDate() + " 收到，还没上报").toList();
        return pending.isEmpty()
                ? new Evidence(23, "OK", "期间内不良反应 " + ad.size() + " 件，都已上报。", List.of())
                : new Evidence(23, "WARN", "期间内不良反应 " + ad.size() + " 件，" + pending.size() + " 件还没上报。", limit(pending));
    }

    private Evidence recallCheck(Ctx c) {
        long inPeriod = recalls.countByOpenedAtGreaterThanEqualAndOpenedAtLessThan(c.start(), c.end());
        List<String> open = recalls.findByStatus(CaseStatus.OPEN).stream().map(r -> r.getRecallNo() + "：" + r.getOpenedAt().toLocalDate() + " 发起，还没关闭").toList();
        return new Evidence(24, open.isEmpty() ? "INFO" : "WARN", "期间内召回 " + inPeriod + " 次" + (open.isEmpty() ? "，没有未关闭的召回。" : "，" + open.size() + " 次还没关闭。")
                + "召回制度和召回演练记录请人工核对。", limit(open));
    }

    // ---- 工具 ----

    private Map<Long, Batch> batchesOf(List<QualityDecision> list) {
        return batches.findAllById(list.stream().map(QualityDecision::getBatchId).distinct().toList()).stream()
                .collect(Collectors.toMap(Batch::getId, Function.identity()));
    }

    private static boolean isFg(Ctx c, Batch b) {
        Item i = c.items().get(b.getItemId());
        return i != null && i.getItemType() == ItemType.FG;
    }

    private static String name(Ctx c, Long itemId) {
        Item i = c.items().get(itemId);
        return i == null ? String.valueOf(itemId) : i.getName() + "（" + i.getCode() + "）";
    }

    private static List<String> limit(List<String> list) {
        if (list.size() <= MAX_DETAILS) {
            return list;
        }
        List<String> out = new ArrayList<>(list.subList(0, MAX_DETAILS));
        out.add("……另有 " + (list.size() - MAX_DETAILS) + " 处");
        return out;
    }
}
