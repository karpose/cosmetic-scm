package com.cosmetic.scm.oemcontract;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.cosmetic.scm.attachment.AttachmentRepository;
import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.DocNoService;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.ContractStatus;
import com.cosmetic.scm.domain.ContractSupply;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.MoStatus;
import com.cosmetic.scm.domain.MoType;
import com.cosmetic.scm.domain.OwnerType;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.rules.ContractCoverage;
import com.cosmetic.scm.domain.rules.ContractCoverage.Term;
import com.cosmetic.scm.master.BomLineRepository;
import com.cosmetic.scm.master.BomRepository;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.production.MfgOrder;
import com.cosmetic.scm.production.MfgOrderRepository;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.CurrentUser;
import com.cosmetic.scm.security.SignatureService;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 委托生产合同（《化妆品生产质量管理规范》第五十二条；检查要点委托生产版第 8 项）。
 * 采购、生产、质量岗位登记合同和覆盖的成品后提交；质量安全负责人对照双方签字盖章的扫描件（签批前必须上传）逐项确认约定事项、
 * 输入密码签批生效，不能是登记人或提交人。到期不用操作（按日期算），提前终止要写原因。下委托单时核对有没有覆盖这个成品的有效合同：
 * 默认只提醒，打开 scm.oem.require-contract-for-order 后没有合同不能下单。规则见 docs/PLM-默认规则.md 的“委托生产合同”。
 */
@Service
@RequiredArgsConstructor
public class OemContractService {

    /** 到期前多少天提醒续签。 */
    public static final int EXPIRY_WARN_DAYS = 30;

    /** “委托单核对”列出最近多少天下的单（还没完成的全部列出）。 */
    public static final int ORDER_LOOKBACK_DAYS = 90;

    /**
     * 签批时要逐项确认合同里写了的约定事项。按检查要点第 8 项（委托事项、期限、质量安全责任、物料采购、检验、放行）、
     * 第 9 项（监督）、第 18 项（受托方保存的记录和调阅）、第 24 项（召回时停止生产）整理，是默认清单，要质量部确认。
     */
    public static final Map<String, String> CLAUSES = new LinkedHashMap<>();

    static {
        CLAUSES.put("SCOPE", "委托生产的产品和委托事项");
        CLAUSES.put("TERM", "委托期限");
        CLAUSES.put("QUALITY", "双方的质量安全责任");
        CLAUSES.put("PROCUREMENT", "物料采购分工和物料验收标准");
        CLAUSES.put("INSPECTION", "物料检验和产品出厂检验");
        CLAUSES.put("RELEASE", "受托方出厂放行、委托方上市放行");
        CLAUSES.put("SUPERVISION", "委托方对生产活动的监督和审核");
        CLAUSES.put("RECORDS", "生产质量记录的保存和委托方调阅");
        CLAUSES.put("RECALL", "召回时停止生产并配合召回");
    }

    private static final List<MoStatus> OPEN_ORDERS = List.of(MoStatus.PLANNED, MoStatus.ISSUED);

    private final OemContractRepository contracts;
    private final OemContractProductRepository contractProducts;
    private final MfgOrderRepository orders;
    private final BomRepository boms;
    private final BomLineRepository bomLines;
    private final AttachmentRepository attachments;
    private final MasterLookup lookup;
    private final SignatureService signature;
    private final DocNoService docNo;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    @Value("${scm.oem.require-contract-for-order:false}")
    private boolean requireForOrder;

    public record SaveReq(@NotNull(message = "请选择受托生产企业") Long factoryId,
                          @NotBlank(message = "请填写合同名称") @Size(max = 100, message = "合同名称最多 100 个字") String title,
                          @Size(max = 60, message = "合同编号最多 60 个字") String extNo,
                          @NotNull(message = "请选择签订日期") LocalDate signedOn,
                          @NotNull(message = "请选择合同开始日期") LocalDate validFrom,
                          @NotNull(message = "请选择合同结束日期") LocalDate validTo,
                          @NotNull(message = "请选择原料由谁采购") ContractSupply rmSupply,
                          @NotNull(message = "请选择包材由谁采购") ContractSupply pkSupply,
                          @NotEmpty(message = "请至少选一个委托生产的成品") @Size(max = 300, message = "一份合同最多 300 个成品") List<Long> productIds,
                          @Size(max = 500, message = "其他约定最多 500 个字") String otherTerms) {
    }

    public record ApproveReq(List<String> checked, @NotBlank(message = "请输入签批密码") String password) {
    }

    public record NoteReq(@NotBlank(message = "请写明原因") @Size(max = 300, message = "原因最多 300 个字") String note) {
    }

    public record ProductRef(Long id, String code, String name) {
    }

    /**
     * state：DRAFT 草稿、PENDING 待签批、NOT_STARTED 未开始、IN_EFFECT 执行中、EXPIRED 已到期、TERMINATED 已终止、VOID 已作废。
     * expiringSoon：执行中、30 天内到期且没有续签（同一受托厂另有到期更晚的生效合同覆盖全部成品算续签）。
     * mismatches：合同约定的物料采购分工和成品现行 BOM 的供料方式不一致的地方。hasFile：有没有上传合同扫描件。
     * licenseGaps：合同里的眼部护肤类、儿童护肤类成品，受托厂许可项目没有标注相应生产条件的（每个成品一句）。
     */
    public record ContractView(Long id, String contractNo, String extNo, Long factoryId, String factoryCode, String factoryName, String title,
                               LocalDate signedOn, LocalDate validFrom, LocalDate validTo, ContractSupply rmSupply, String rmSupplyLabel,
                               ContractSupply pkSupply, String pkSupplyLabel, List<ProductRef> products, String productNames, String otherTerms,
                               List<String> checked, ContractStatus status, String statusLabel, String state, String stateLabel, Long daysLeft,
                               boolean expiringSoon, List<String> mismatches, List<String> licenseGaps, boolean hasFile, String rejectNote, String createdBy, LocalDateTime createdAt,
                               String submittedBy, LocalDateTime submittedAt, String approvedBy, LocalDateTime approvedAt, String terminatedBy,
                               LocalDateTime terminatedAt, String endReason) {
    }

    public record Option(String code, String label) {
    }

    /** requireForOrder：是否已打开“没有合同不能下委托单”。 */
    public record Overview(List<ContractView> contracts, List<Option> clauses, List<Option> supplies, boolean requireForOrder) {
    }

    /** 一张委托单下单时有没有合同依据。 */
    public record OrderCheck(Long moId, String moNo, Long factoryId, String factoryName, Long fgItemId, String fgCode, String fgName,
                             MoStatus status, String statusLabel, LocalDateTime createdAt, Long contractId, String contractNo, boolean covered) {
    }

    /**
     * 下委托单前的核对。applicable：选的是受托生产企业（自有工厂不用合同）。
     * licenseMissing：成品要求、生产企业许可项目没有标注的生产条件（自有工厂也核对），有的话不能下单。
     */
    public record CheckView(boolean applicable, boolean required, boolean covered, Long contractId, String contractNo, LocalDate validTo,
                            List<String> mismatches, List<String> licenseMissing) {
    }

    /** 总览待办：还没完成的委托单、执行中或未开始的合同里，受托厂许可项目没有标注成品要求的生产条件。kind：ORDER 委托单、CONTRACT 合同。 */
    public record LicenseGap(String kind, Long refId, String refNo, Long factoryId, String factoryName, Long fgItemId, String fgName, List<String> missing) {
    }

    // ---------- 查询 ----------

    @Transactional(readOnly = true)
    public Overview overview() {
        return new Overview(views(contracts.findTop300ByOrderByIdDesc()),
                CLAUSES.entrySet().stream().map(e -> new Option(e.getKey(), e.getValue())).toList(),
                Arrays.stream(ContractSupply.values()).map(s -> new Option(s.name(), s.getLabel())).toList(), requireForOrder);
    }

    /** 全部合同，导出用。 */
    @Transactional(readOnly = true)
    public List<ContractView> listAll() {
        return views(contracts.findAllByOrderByIdDesc());
    }

    @Transactional(readOnly = true)
    public List<ContractView> pendingApproval() {
        return views(contracts.findByStatusInOrderById(List.of(ContractStatus.PENDING)));
    }

    /** 执行中、30 天内到期、还没续签的合同。 */
    @Transactional(readOnly = true)
    public List<ContractView> expiringSoon() {
        return views(contracts.findByStatusInOrderById(List.of(ContractStatus.ACTIVE))).stream().filter(ContractView::expiringSoon).toList();
    }

    /** 还没完成的委托单和最近 90 天下的委托单，下单时有没有合同依据。 */
    @Transactional(readOnly = true)
    public List<OrderCheck> orderChecks() {
        LocalDateTime since = time.today().minusDays(ORDER_LOOKBACK_DAYS).atStartOfDay();
        Map<Long, MfgOrder> map = new LinkedHashMap<>();
        orders.findByMoTypeAndStatusIn(MoType.OEM, OPEN_ORDERS).forEach(m -> map.put(m.getId(), m));
        orders.findByMoTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(MoType.OEM, since, time.now().plusDays(1)).stream()
                .filter(m -> m.getStatus() != MoStatus.CANCELLED).forEach(m -> map.put(m.getId(), m));
        return checks(map.values().stream().sorted(Comparator.comparing(MfgOrder::getId).reversed()).toList());
    }

    /** 还没完成、下单时没有合同依据的委托单（总览待办）。 */
    @Transactional(readOnly = true)
    public List<OrderCheck> uncoveredOpenOrders() {
        return checks(orders.findByMoTypeAndStatusIn(MoType.OEM, OPEN_ORDERS)).stream().filter(c -> !c.covered()).toList();
    }

    /** 下委托单前核对：现在有没有覆盖“受托厂 + 成品”的有效合同，约定的采购分工和 BOM 是否一致。 */
    @Transactional(readOnly = true)
    public CheckView check(Long factoryId, Long fgItemId) {
        Partner f = lookup.partner(factoryId);
        Item fg = lookup.item(fgItemId);
        if (f.getKind() != PartnerKind.OEM) {
            return new CheckView(false, requireForOrder, true, null, null, null, List.of(), f.missingConditionsFor(fg));
        }
        var cover = OemContractTerms.covering(contracts, contractProducts, factoryId, fgItemId, OemContractTerms.stamp(time.now()));
        if (cover.isEmpty()) {
            return new CheckView(true, requireForOrder, false, null, null, null, List.of(), f.missingConditionsFor(fg));
        }
        OemContract c = contracts.findById(cover.get().id()).orElseThrow();
        List<String> mismatches = ContractCoverage.supplyMismatches(fg.getName(), c.getRmSupply(), c.getPkSupply(),
                bomOf(List.of(fgItemId), lookup.itemMap()).getOrDefault(fgItemId, List.of()));
        return new CheckView(true, requireForOrder, true, c.getId(), c.getContractNo(), c.getValidTo(), mismatches, f.missingConditionsFor(fg));
    }

    /** 受托厂许可项目没有标注成品要求的生产条件：还没完成的委托单（标注或类别是下单后改的）、执行中和未开始的合同。 */
    @Transactional(readOnly = true)
    public List<LicenseGap> licenseGaps() {
        Map<Long, Item> im = lookup.itemMap();
        Map<Long, Partner> pm = lookup.partnerMap();
        List<LicenseGap> out = new ArrayList<>();
        for (MfgOrder m : orders.findByMoTypeAndStatusIn(MoType.OEM, OPEN_ORDERS)) {
            Partner f = pm.get(m.getFactoryId());
            Item fg = im.get(m.getFgItemId());
            if (f != null && fg != null && !f.missingConditionsFor(fg).isEmpty()) {
                out.add(new LicenseGap("ORDER", m.getId(), m.getMoNo(), f.getId(), f.getName(), fg.getId(), fg.getName(), f.missingConditionsFor(fg)));
            }
        }
        LocalDate today = time.today();
        List<OemContract> live = contracts.findByStatusInOrderById(List.of(ContractStatus.ACTIVE)).stream()
                .filter(c -> !today.isAfter(c.getValidTo())).toList();
        Map<Long, List<Long>> productsOf = live.isEmpty() ? Map.of() : contractProducts.findByContractIdIn(live.stream().map(OemContract::getId).toList()).stream()
                .collect(Collectors.groupingBy(OemContractProduct::getContractId, Collectors.mapping(OemContractProduct::getFgItemId, Collectors.toList())));
        for (OemContract c : live) {
            Partner f = pm.get(c.getFactoryId());
            if (f == null) {
                continue;
            }
            for (Long pid : productsOf.getOrDefault(c.getId(), List.of())) {
                Item fg = im.get(pid);
                if (fg != null && !f.missingConditionsFor(fg).isEmpty()) {
                    out.add(new LicenseGap("CONTRACT", c.getId(), c.getContractNo(), f.getId(), f.getName(), fg.getId(), fg.getName(), f.missingConditionsFor(fg)));
                }
            }
        }
        return out;
    }

    // ---------- 写操作 ----------

    @Transactional
    public ContractView create(SaveReq req) {
        Partner f = validate(req);
        List<Long> ids = distinct(req.productIds());
        OemContract c = new OemContract();
        c.setContractNo(docNo.next("HT", time.today()));
        apply(c, req);
        c.setStatus(ContractStatus.DRAFT);
        c.setCreatedBy(currentUser.usernameOrSystem());
        c.setCreatedAt(time.now());
        contracts.save(c);
        ids.forEach(id -> contractProducts.save(new OemContractProduct(c.getId(), id)));
        audit.log("登记委托生产合同", c.getContractNo(), describe(c, f, ids));
        return views(List.of(c)).get(0);
    }

    /** 修改草稿（含退回的）。先锁合同行（事务第一句）。 */
    @Transactional
    public ContractView update(Long id, SaveReq req) {
        OemContract c = lock(id);
        require(c, ContractStatus.DRAFT, "修改");
        String before = describe(c, lookup.partner(c.getFactoryId()), productIds(c.getId()));
        Partner f = validate(req);
        List<Long> ids = distinct(req.productIds());
        apply(c, req);
        contracts.save(c);
        contractProducts.deleteByContractId(c.getId());
        contractProducts.flush();
        ids.forEach(pid -> contractProducts.save(new OemContractProduct(c.getId(), pid)));
        audit.log("修改委托生产合同", c.getContractNo(), "修改前：" + before + "；修改后：" + describe(c, f, ids));
        return views(List.of(c)).get(0);
    }

    @Transactional
    public ContractView submit(Long id) {
        OemContract c = lock(id);
        require(c, ContractStatus.DRAFT, "提交签批");
        c.setStatus(ContractStatus.PENDING);
        c.setSubmittedBy(currentUser.usernameOrSystem());
        c.setSubmittedAt(time.now());
        c.setRejectNote(null);
        contracts.save(c);
        audit.log("提交委托生产合同签批", c.getContractNo(), lookup.partner(c.getFactoryId()).getName() + "，" + c.getValidFrom() + " 至 " + c.getValidTo());
        return views(List.of(c)).get(0);
    }

    /**
     * 质量安全负责人签批：不是登记人或提交人，约定事项全部确认，有扫描件，受托厂在合格名录内且生产许可证有效，
     * 合同里的眼部护肤类、儿童护肤类成品，受托厂许可项目标注了相应生产条件。
     */
    @Transactional
    public ContractView approve(Long id, ApproveReq req) {
        OemContract c = lock(id);
        require(c, ContractStatus.PENDING, "签批");
        AppUser signer = signature.verify(req.password());
        if (signer.getUsername().equals(c.getCreatedBy()) || signer.getUsername().equals(c.getSubmittedBy())) {
            throw new BizException("签批人不能是合同的登记人或提交人");
        }
        Set<String> checked = req.checked() == null ? Set.of() : Set.copyOf(req.checked());
        List<String> missing = CLAUSES.entrySet().stream().filter(e -> !checked.contains(e.getKey())).map(Map.Entry::getValue).toList();
        if (!missing.isEmpty()) {
            throw new BizException("合同里的约定事项没有全部确认：" + String.join("、", missing) + "。合同没有写明的，请退回补签补充协议");
        }
        if (!attachments.existsByOwnerTypeAndOwnerId(OwnerType.OEM_CONTRACT, c.getId())) {
            throw new BizException("还没有上传合同扫描件，不能签批");
        }
        Partner f = lookup.partner(c.getFactoryId());
        requireFactory(f);
        List<String> gaps = licenseGaps(f, productIds(c.getId()), lookup.itemMap());
        if (!gaps.isEmpty()) {
            throw new BizException(f.getName() + " 的生产许可项目没有标注相应生产条件，不能签批：" + String.join("；", gaps)
                    + "。许可证上已有标注的，请先在主数据里给受托厂勾上；没有的，请退回把这些成品移出合同");
        }
        c.setStatus(ContractStatus.ACTIVE);
        c.setClauses(String.join(",", CLAUSES.keySet()));
        c.setApprovedBy(signer.getUsername());
        c.setApprovedAt(time.now());
        contracts.save(c);
        audit.log("签批委托生产合同", c.getContractNo(), f.getName() + "，" + c.getValidFrom() + " 至 " + c.getValidTo() + "，约定事项全部确认");
        return views(List.of(c)).get(0);
    }

    /** 退回：回到草稿，登记人改好后重新提交。 */
    @Transactional
    public ContractView reject(Long id, NoteReq req) {
        OemContract c = lock(id);
        require(c, ContractStatus.PENDING, "退回");
        c.setStatus(ContractStatus.DRAFT);
        c.setRejectNote(req.note().trim());
        contracts.save(c);
        audit.log("退回委托生产合同", c.getContractNo(), lookup.partner(c.getFactoryId()).getName(), req.note().trim());
        return views(List.of(c)).get(0);
    }

    /** 提前终止（更换受托厂、解除合作）。终止前已下的委托单仍按这份合同执行，之后下的单不再有依据。 */
    @Transactional
    public ContractView terminate(Long id, NoteReq req) {
        OemContract c = lock(id);
        require(c, ContractStatus.ACTIVE, "终止");
        c.setStatus(ContractStatus.TERMINATED);
        c.setTerminatedBy(currentUser.usernameOrSystem());
        c.setTerminatedAt(OemContractTerms.stamp(time.now()));
        c.setEndReason(req.note().trim());
        contracts.save(c);
        audit.log("终止委托生产合同", c.getContractNo(), lookup.partner(c.getFactoryId()).getName() + "，原定 " + c.getValidTo() + " 到期", req.note().trim());
        return views(List.of(c)).get(0);
    }

    /** 作废草稿（登记错了、没签成）。 */
    @Transactional
    public ContractView voidDraft(Long id, NoteReq req) {
        OemContract c = lock(id);
        require(c, ContractStatus.DRAFT, "作废");
        c.setStatus(ContractStatus.VOID);
        c.setEndReason(req.note().trim());
        contracts.save(c);
        audit.log("作废委托生产合同", c.getContractNo(), lookup.partner(c.getFactoryId()).getName(), req.note().trim());
        return views(List.of(c)).get(0);
    }

    // ---------- 内部 ----------

    private Partner validate(SaveReq req) {
        Partner f = lookup.partner(req.factoryId());
        if (f.getKind() != PartnerKind.OEM) {
            throw new BizException(f.getName() + " 不是受托生产企业，委托生产合同只和受托生产企业签");
        }
        if (req.validTo().isBefore(req.validFrom())) {
            throw new BizException("合同结束日期不能早于开始日期");
        }
        if (req.signedOn().isAfter(time.today())) {
            throw new BizException("签订日期不能晚于今天");
        }
        for (Long pid : req.productIds()) {
            if (pid == null) {
                throw new BizException("请选择委托生产的成品");
            }
            Item i = lookup.item(pid);
            if (i.getItemType() != ItemType.FG) {
                throw new BizException(i.getName() + " 不是成品，合同里只列委托生产的成品");
            }
        }
        return f;
    }

    private void requireFactory(Partner f) {
        if (!f.isQualified()) {
            throw new BizException(f.getName() + " 不在合格名录内，不能签批委托生产合同");
        }
        if (f.getLicenseNo() == null || f.getLicenseNo().isBlank()) {
            throw new BizException(f.getName() + " 还没有登记生产许可证编号，请先在主数据里补上");
        }
        if (f.licenseExpired(time.today())) {
            throw new BizException(f.getName() + " 的生产许可证已于 " + f.getLicenseExpiry() + " 过期，不能签批委托生产合同");
        }
    }

    private static void apply(OemContract c, SaveReq req) {
        c.setFactoryId(req.factoryId());
        c.setTitle(req.title().trim());
        c.setExtNo(blankToNull(req.extNo()));
        c.setSignedOn(req.signedOn());
        c.setValidFrom(req.validFrom());
        c.setValidTo(req.validTo());
        c.setRmSupply(req.rmSupply());
        c.setPkSupply(req.pkSupply());
        c.setOtherTerms(blankToNull(req.otherTerms()));
    }

    private String describe(OemContract c, Partner f, List<Long> ids) {
        Map<Long, Item> im = lookup.itemMap();
        return f.getName() + "，" + c.getTitle() + (c.getExtNo() == null ? "" : "（" + c.getExtNo() + "）") + "，" + c.getSignedOn() + " 签订，"
                + c.getValidFrom() + " 至 " + c.getValidTo() + "，成品：" + ids.stream().map(im::get).filter(Objects::nonNull).map(Item::getCode)
                .collect(Collectors.joining("、")) + "，原料" + c.getRmSupply().getLabel() + "，包材" + c.getPkSupply().getLabel();
    }

    /** 每个缺标注的成品一句：“眼霜（眼部护肤类）”。 */
    private static List<String> licenseGaps(Partner f, List<Long> productIds, Map<Long, Item> im) {
        List<String> out = new ArrayList<>();
        for (Long pid : productIds) {
            Item fg = im.get(pid);
            if (fg != null) {
                List<String> missing = f.missingConditionsFor(fg);
                if (!missing.isEmpty()) {
                    out.add(fg.getName() + "（" + String.join("、", missing) + "）");
                }
            }
        }
        return out;
    }

    private List<Long> productIds(Long contractId) {
        return contractProducts.findByContractId(contractId).stream().map(OemContractProduct::getFgItemId).toList();
    }

    private static List<Long> distinct(List<Long> ids) {
        return new ArrayList<>(new LinkedHashSet<>(ids));
    }

    private OemContract lock(Long id) {
        return contracts.lockById(id).orElseThrow(() -> BizException.notFound("委托生产合同"));
    }

    private static void require(OemContract c, ContractStatus status, String action) {
        if (c.getStatus() != status) {
            throw new BizException("合同 " + c.getContractNo() + " 当前为" + c.getStatus().getLabel() + "，不能" + action);
        }
    }

    private List<OrderCheck> checks(List<MfgOrder> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, Item> im = lookup.itemMap();
        Map<Long, Partner> pm = lookup.partnerMap();
        List<Term> terms = OemContractTerms.ofFactories(contracts, contractProducts,
                list.stream().map(MfgOrder::getFactoryId).filter(Objects::nonNull).collect(Collectors.toSet()));
        return list.stream().map(m -> {
            var cover = ContractCoverage.covering(terms, m.getFactoryId(), m.getFgItemId(), m.getCreatedAt());
            Item fg = im.get(m.getFgItemId());
            Partner f = pm.get(m.getFactoryId());
            return new OrderCheck(m.getId(), m.getMoNo(), m.getFactoryId(), f == null ? null : f.getName(), m.getFgItemId(), fg == null ? null : fg.getCode(),
                    fg == null ? null : fg.getName(), m.getStatus(), m.getStatus().getLabel(), m.getCreatedAt(), cover.map(Term::id).orElse(null),
                    cover.map(Term::contractNo).orElse(null), cover.isPresent());
        }).toList();
    }

    private Map<Long, List<ContractCoverage.Line>> bomOf(List<Long> fgIds, Map<Long, Item> im) {
        return OemContractTerms.bomLines(boms, bomLines, fgIds, im);
    }

    private List<ContractView> views(List<OemContract> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        LocalDate today = time.today();
        Map<Long, Item> im = lookup.itemMap();
        Map<Long, Partner> pm = lookup.partnerMap();
        List<Long> ids = list.stream().map(OemContract::getId).toList();
        Map<Long, List<Long>> productsOf = contractProducts.findByContractIdIn(ids).stream()
                .collect(Collectors.groupingBy(OemContractProduct::getContractId, Collectors.mapping(OemContractProduct::getFgItemId, Collectors.toList())));
        Set<Long> withFile = new HashSet<>(attachments.findOwnerIdsWithFiles(OwnerType.OEM_CONTRACT, ids));
        Map<Long, List<ContractCoverage.Line>> bom = bomOf(productsOf.values().stream().flatMap(List::stream).distinct().toList(), im);
        // 续签判断要看同一受托厂其他生效的合同（不一定在这一页里）
        List<Term> active = OemContractTerms.terms(contracts.findByFactoryIdInAndStatusIn(
                list.stream().map(OemContract::getFactoryId).collect(Collectors.toSet()), List.of(ContractStatus.ACTIVE)), contractProducts);
        return list.stream().map(c -> {
            List<Long> pids = productsOf.getOrDefault(c.getId(), List.of());
            List<ProductRef> refs = pids.stream().map(im::get).filter(Objects::nonNull).sorted(Comparator.comparing(Item::getCode))
                    .map(i -> new ProductRef(i.getId(), i.getCode(), i.getName())).toList();
            String state = state(c, today);
            Long daysLeft = "IN_EFFECT".equals(state) || "NOT_STARTED".equals(state) ? ChronoUnit.DAYS.between(today, c.getValidTo()) : null;
            boolean renewed = !pids.isEmpty() && pids.stream().allMatch(pid -> active.stream().anyMatch(t -> !t.id().equals(c.getId())
                    && t.factoryId().equals(c.getFactoryId()) && t.products().contains(pid) && t.validTo().isAfter(c.getValidTo())));
            boolean expiring = "IN_EFFECT".equals(state) && daysLeft != null && daysLeft <= EXPIRY_WARN_DAYS && !renewed;
            List<String> mismatches = c.getStatus() == ContractStatus.VOID || c.getStatus() == ContractStatus.TERMINATED || "EXPIRED".equals(state) ? List.of()
                    : refs.stream().flatMap(r -> ContractCoverage.supplyMismatches(r.name(), c.getRmSupply(), c.getPkSupply(), bom.getOrDefault(r.id(), List.of())).stream())
                    .toList();
            List<String> checked = c.getClauses() == null || c.getClauses().isBlank() ? List.of() : List.of(c.getClauses().split(","));
            Partner f = pm.get(c.getFactoryId());
            boolean live = c.getStatus() != ContractStatus.VOID && c.getStatus() != ContractStatus.TERMINATED && !"EXPIRED".equals(state);
            List<String> gaps = live && f != null ? licenseGaps(f, refs.stream().map(ProductRef::id).toList(), im) : List.of();
            return new ContractView(c.getId(), c.getContractNo(), c.getExtNo(), c.getFactoryId(), f == null ? null : f.getCode(), f == null ? null : f.getName(),
                    c.getTitle(), c.getSignedOn(), c.getValidFrom(), c.getValidTo(), c.getRmSupply(), c.getRmSupply().getLabel(), c.getPkSupply(),
                    c.getPkSupply().getLabel(), refs, refs.stream().map(ProductRef::name).collect(Collectors.joining("、")), c.getOtherTerms(), checked,
                    c.getStatus(), c.getStatus().getLabel(), state, stateLabel(state), daysLeft, expiring, mismatches, gaps, withFile.contains(c.getId()),
                    c.getRejectNote(), c.getCreatedBy(), c.getCreatedAt(), c.getSubmittedBy(), c.getSubmittedAt(), c.getApprovedBy(), c.getApprovedAt(),
                    c.getTerminatedBy(), c.getTerminatedAt(), c.getEndReason());
        }).toList();
    }

    static String state(OemContract c, LocalDate today) {
        return switch (c.getStatus()) {
            case ACTIVE -> today.isBefore(c.getValidFrom()) ? "NOT_STARTED" : today.isAfter(c.getValidTo()) ? "EXPIRED" : "IN_EFFECT";
            default -> c.getStatus().name();
        };
    }

    static String stateLabel(String state) {
        return switch (state) {
            case "NOT_STARTED" -> "未开始";
            case "IN_EFFECT" -> "执行中";
            case "EXPIRED" -> "已到期";
            default -> ContractStatus.valueOf(state).getLabel();
        };
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
