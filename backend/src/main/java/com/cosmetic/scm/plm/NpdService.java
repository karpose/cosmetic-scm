package com.cosmetic.scm.plm;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.DocNoService;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.FilingStatus;
import com.cosmetic.scm.domain.FilingType;
import com.cosmetic.scm.domain.FormulaStatus;
import com.cosmetic.scm.domain.ItemType;
import com.cosmetic.scm.domain.NpdStatus;
import com.cosmetic.scm.domain.TrialKind;
import com.cosmetic.scm.domain.TrialResult;
import com.cosmetic.scm.master.Item;
import com.cosmetic.scm.master.ItemRepository;
import com.cosmetic.scm.master.MasterLookup;
import com.cosmetic.scm.quality.QualitySpecService;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.CurrentUser;
import com.cosmetic.scm.security.SignatureService;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 新品立项：管理员批准立项 → 开发中记录小试、中试，关联成品 → 上市检查全部通过后提交 → QSO 批准上市。
 * 上市检查不另存数据，每次都从配方、备案、合规资料、稳定性、质量标准这些模块实时去查。
 * 规则都是默认值，见 docs/PLM-默认规则.md。
 */
@Service
@RequiredArgsConstructor
public class NpdService {

    private static final List<NpdStatus> IN_PROGRESS = List.of(NpdStatus.PROPOSED, NpdStatus.ACTIVE, NpdStatus.LAUNCH_REVIEW);

    private final NpdProjectRepository projects;
    private final NpdTrialRepository trials;
    private final ItemRepository items;
    private final FormulaVersionRepository versions;
    private final ProductFilingRepository filings;
    private final ComplianceService compliance;
    private final StabilityService stability;
    private final QualitySpecService specs;
    private final LabelArtworkRepository artworks;
    private final ArtworkService artworkService;
    private final com.cosmetic.scm.master.MasterService master;
    private final MasterLookup lookup;
    private final SignatureService signature;
    private final DocNoService docNo;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    public record CreateReq(@NotBlank(message = "请填写产品名称") @Size(max = 100) String name, @NotBlank(message = "请填写品类") @Size(max = 60) String category,
                            @NotNull(message = "请选择备案类型") FilingType filingType, @Size(max = 300) String claims,
                            @NotNull(message = "请选择计划上市日期") LocalDate targetLaunch, @Size(max = 500) String note) {
    }

    public record LinkReq(@NotNull(message = "请选择成品") Long fgItemId) {
    }

    public record TrialReq(@NotNull(message = "请选择试制类型") TrialKind kind, @NotNull(message = "请选择试制日期") LocalDate trialDate, Long formulaVersionId,
                           @Size(max = 60) String batchSize, @NotNull(message = "请选择结论") TrialResult result,
                           @NotBlank(message = "请填写试制情况") @Size(max = 500) String summary) {
    }

    public record NoteReq(@NotBlank(message = "请写明原因") @Size(max = 300) String note) {
    }

    public record ApproveReq(@NotBlank(message = "请输入签批密码") String password) {
    }

    public record ProjectRow(Long id, String projectNo, String name, String category, FilingType filingType, String filingTypeLabel, String claims, LocalDate targetLaunch,
                             boolean late, String note, Long fgItemId, String fgCode, String fgName, NpdStatus status, String statusLabel, int trials, boolean pilotPassed,
                             String createdBy, LocalDateTime createdAt, String approvedBy, LocalDateTime approvedAt, String rejectNote, String submittedBy,
                             String launchedBy, LocalDateTime launchedAt, String cancelReason) {
    }

    public record TrialView(Long id, String trialNo, TrialKind kind, String kindLabel, LocalDate trialDate, Long formulaVersionId, Integer versionNo, String batchSize,
                            TrialResult result, String resultLabel, String summary, String recordedBy, LocalDateTime recordedAt) {
    }

    /** 上市检查的一项。route 是去哪个页面处理。 */
    public record Check(String title, boolean ok, String detail, String route) {
    }

    public record Detail(ProjectRow project, List<TrialView> trials, List<Check> checks, boolean ready) {
    }

    public record NpdSummary(
            int totalCount, int proposedCount, int activeCount, int launchReviewCount,
            int launchedCount, int lateCount, int rejectedOrCancelledCount) {
    }

    @Transactional(readOnly = true)
    public NpdSummary summary() {
        LocalDate today = time.today();
        List<NpdProject> all = projects.findAll();
        int proposed = 0, active = 0, launchReview = 0, launched = 0, late = 0, closed = 0;
        for (NpdProject p : all) {
            if (p.getStatus() == NpdStatus.PROPOSED) {
                proposed++;
            } else if (p.getStatus() == NpdStatus.ACTIVE) {
                active++;
                if (p.getTargetLaunch() != null && p.getTargetLaunch().isBefore(today)) {
                    late++;
                }
            } else if (p.getStatus() == NpdStatus.LAUNCH_REVIEW) {
                launchReview++;
                if (p.getTargetLaunch() != null && p.getTargetLaunch().isBefore(today)) {
                    late++;
                }
            } else if (p.getStatus() == NpdStatus.LAUNCHED) {
                launched++;
            } else {
                closed++;
            }
        }
        return new NpdSummary(all.size(), proposed, active, launchReview, launched, late, closed);
    }

    @Transactional(readOnly = true)
    public List<ProjectRow> list() {
        return rows(projects.findTop300ByOrderByIdDesc());
    }

    @Transactional(readOnly = true)
    public List<ProjectRow> listAll() {
        return rows(projects.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id")));
    }

    @Transactional(readOnly = true)
    public Detail detail(Long id) {
        return detailOf(projects.findById(id).orElseThrow(() -> BizException.notFound("新品项目")));
    }

    @Transactional(readOnly = true)
    public List<ProjectRow> byStatus(NpdStatus status) {
        return rows(projects.findByStatusOrderById(status));
    }

    /** 过了计划上市日期还没上市的。 */
    @Transactional(readOnly = true)
    public List<ProjectRow> late(LocalDate today) {
        return rows(projects.findByStatusInAndTargetLaunchBeforeOrderByTargetLaunch(List.of(NpdStatus.ACTIVE, NpdStatus.LAUNCH_REVIEW), today));
    }

    @Transactional
    public Detail create(CreateReq req) {
        if (req.targetLaunch().isBefore(time.today())) {
            throw new BizException("计划上市日期不能早于今天");
        }
        NpdProject p = new NpdProject();
        p.setProjectNo(docNo.next("NP", time.today()));
        p.setName(req.name().trim());
        p.setCategory(req.category().trim());
        p.setFilingType(req.filingType());
        p.setClaims(blankToNull(req.claims()));
        p.setTargetLaunch(req.targetLaunch());
        p.setNote(blankToNull(req.note()));
        p.setStatus(NpdStatus.PROPOSED);
        p.setCreatedBy(currentUser.usernameOrSystem());
        p.setCreatedAt(time.now());
        projects.save(p);
        audit.log("新品立项申请", p.getProjectNo(), p.getName() + "，" + p.getCategory() + "，" + req.filingType().getLabel() + "，计划 " + p.getTargetLaunch() + " 上市");
        return detailOf(p);
    }

    @Transactional
    public Detail approve(Long id) {
        NpdProject p = lock(id);
        requireStatus(p, NpdStatus.PROPOSED, "批准立项");
        String me = currentUser.usernameOrSystem();
        if (me.equals(p.getCreatedBy())) {
            throw new BizException("批准人不能是申请人");
        }
        p.setStatus(NpdStatus.ACTIVE);
        p.setApprovedBy(me);
        p.setApprovedAt(time.now());
        projects.save(p);
        audit.log("批准新品立项", p.getProjectNo(), p.getName());
        return detailOf(p);
    }

    @Transactional
    public Detail reject(Long id, NoteReq req) {
        NpdProject p = lock(id);
        requireStatus(p, NpdStatus.PROPOSED, "不予立项");
        p.setStatus(NpdStatus.REJECTED);
        p.setRejectNote(req.note().trim());
        projects.save(p);
        audit.log("不予立项", p.getProjectNo(), p.getName(), req.note().trim());
        return detailOf(p);
    }

    /** 关联成品。一个成品只能属于一个进行中或已上市的项目，所以先锁物料行，而且是事务第一句。 */
    @Transactional
    public Detail link(Long id, LinkReq req) {
        Item fg = items.lockById(req.fgItemId()).orElseThrow(() -> BizException.notFound("成品"));
        NpdProject p = lock(id);
        requireStatus(p, NpdStatus.ACTIVE, "关联成品");
        if (fg.getItemType() != ItemType.FG) {
            throw new BizException(fg.getName() + " 不是成品");
        }
        List<NpdStatus> taken = new ArrayList<>(IN_PROGRESS);
        taken.add(NpdStatus.LAUNCHED);
        projects.findFirstByFgItemIdAndStatusInAndIdNot(fg.getId(), taken, p.getId()).ifPresent(o -> {
            throw new BizException(fg.getName() + " 已关联项目 " + o.getProjectNo() + "（" + o.getName() + "）");
        });
        Long old = p.getFgItemId();
        p.setFgItemId(fg.getId());
        projects.save(p);
        audit.log("新品关联成品", p.getProjectNo(), (old == null ? "" : lookup.item(old).getCode() + " 改为 ") + fg.getCode() + " " + fg.getName());
        return detailOf(p);
    }

    @Transactional
    public Detail addTrial(Long id, TrialReq req) {
        NpdProject p = lock(id);
        requireStatus(p, NpdStatus.ACTIVE, "记录试制");
        if (req.trialDate().isAfter(time.today())) {
            throw new BizException("试制日期不能晚于今天");
        }
        if (req.kind() == TrialKind.PILOT && req.formulaVersionId() == null) {
            throw new BizException("中试要选对应的配方版本，上市检查按它判断");
        }
        FormulaVersion v = null;
        if (req.formulaVersionId() != null) {
            if (p.getFgItemId() == null) {
                throw new BizException("先关联成品，才能选配方版本");
            }
            v = versions.findById(req.formulaVersionId()).filter(x -> x.getFgItemId().equals(p.getFgItemId()))
                    .orElseThrow(() -> new BizException("配方版本不属于关联的成品"));
        }
        NpdTrial t = new NpdTrial();
        t.setProjectId(p.getId());
        t.setTrialNo(docNo.next("NT", time.today()));
        t.setKind(req.kind());
        t.setTrialDate(req.trialDate());
        t.setFormulaVersionId(v == null ? null : v.getId());
        t.setBatchSize(blankToNull(req.batchSize()));
        t.setResult(req.result());
        t.setSummary(req.summary().trim());
        t.setRecordedBy(currentUser.usernameOrSystem());
        t.setRecordedAt(time.now());
        trials.save(t);
        audit.log("记录试制", p.getProjectNo(), t.getTrialNo() + "，" + req.kind().getLabel() + (v == null ? "" : " V" + v.getVersionNo()) + "，" + req.result().getLabel() + "：" + t.getSummary());
        return detailOf(p);
    }

    @Transactional
    public Detail submitLaunch(Long id) {
        NpdProject p = lock(id);
        requireStatus(p, NpdStatus.ACTIVE, "提交上市");
        requireReady(p);
        p.setStatus(NpdStatus.LAUNCH_REVIEW);
        p.setSubmittedBy(currentUser.usernameOrSystem());
        p.setSubmittedAt(time.now());
        p.setRejectNote(null);
        projects.save(p);
        audit.log("提交新品上市", p.getProjectNo(), p.getName() + "，上市检查全部通过");
        return detailOf(p);
    }

    /** 批准时重新检查一遍：提交以后配方、资料可能又变了。 */
    @Transactional
    public Detail approveLaunch(Long id, ApproveReq req) {
        NpdProject p = lock(id);
        requireStatus(p, NpdStatus.LAUNCH_REVIEW, "批准上市");
        AppUser signer = signature.verify(req.password());
        if (signer.getUsername().equals(p.getSubmittedBy())) {
            throw new BizException("批准人不能是提交人");
        }
        requireReady(p);
        p.setStatus(NpdStatus.LAUNCHED);
        p.setLaunchedBy(signer.getUsername());
        p.setLaunchedAt(time.now());
        projects.save(p);
        audit.log("批准新品上市", p.getProjectNo(), p.getName() + "，成品 " + lookup.item(p.getFgItemId()).getCode());
        return detailOf(p);
    }

    @Transactional
    public Detail rejectLaunch(Long id, NoteReq req) {
        NpdProject p = lock(id);
        requireStatus(p, NpdStatus.LAUNCH_REVIEW, "退回");
        p.setStatus(NpdStatus.ACTIVE);
        p.setRejectNote(req.note().trim());
        projects.save(p);
        audit.log("退回新品上市", p.getProjectNo(), p.getName(), req.note().trim());
        return detailOf(p);
    }

    @Transactional
    public Detail cancel(Long id, NoteReq req) {
        NpdProject p = lock(id);
        if (!IN_PROGRESS.contains(p.getStatus())) {
            throw new BizException("项目 " + p.getProjectNo() + " 当前为" + p.getStatus().getLabel() + "，不能取消");
        }
        p.setStatus(NpdStatus.CANCELLED);
        p.setCancelReason(req.note().trim());
        projects.save(p);
        audit.log("取消新品项目", p.getProjectNo(), p.getName(), req.note().trim());
        return detailOf(p);
    }

    /** 上市检查：都从已有模块实时查，不另存。 */
    @Transactional(readOnly = true)
    public List<Check> checks(NpdProject p) {
        List<Check> out = new ArrayList<>();
        Long fgId = p.getFgItemId();
        if (fgId == null) {
            out.add(new Check("关联成品", false, "先在主数据建好成品，再在这里关联", "/master"));
            return out;
        }
        Item fg = lookup.item(fgId);
        out.add(new Check("关联成品", true, fg.getCode() + " " + fg.getName(), "/master"));
        Optional<FormulaVersion> eff = versions.findFirstByFgItemIdAndStatus(fgId, FormulaStatus.EFFECTIVE);
        out.add(new Check("生效配方", eff.isPresent(), eff.map(v -> "V" + v.getVersionNo()).orElse("还没有生效的配方"), "/formulas"));
        Optional<ProductFiling> filing = filings.findByFgItemIdAndStatusOrderByIdDesc(fgId, FilingStatus.FILED).stream().findFirst();
        String filingProblem = filing.isEmpty() ? "还没有完成" + p.getFilingType().getLabel()
                : filing.get().getFilingType() != p.getFilingType() ? "立项是" + p.getFilingType().getLabel() + "，完成的是" + filing.get().getFilingType().getLabel()
                : filing.get().getExpiryDate() != null && filing.get().getExpiryDate().isBefore(time.today()) ? "注册证 " + filing.get().getFilingNo() + " 已过期"
                : null;
        out.add(new Check("备案或注册", filingProblem == null, filingProblem != null ? filingProblem : filing.get().getFilingNo(), "/formulas?tab=filings"));
        ComplianceService.ProductStatus cs = compliance.statuses().stream().filter(s -> s.fgItemId().equals(fgId)).findFirst().orElse(null);
        boolean docsOk = cs != null && cs.complete();
        out.add(new Check("安全评估和标签", docsOk, docsOk ? "当前配方版本已签批" : "当前配方版本的安全评估或标签还没签批", "/compliance"));
        out.add(artworkCheck(eff.orElse(null)));
        StabilityService.ProductCoverage sc = stability.coverage().stream().filter(c -> c.fgItemId().equals(fgId)).findFirst().orElse(null);
        boolean stabOk = sc != null && "OK".equals(sc.state());
        out.add(new Check("稳定性依据", stabOk, stabOk ? "支持 " + sc.supportedMonths() + " 个月" : "当前配方版本没有足够的已签批结论", "/stability?tab=products"));
        var spec = specs.effective(fgId);
        out.add(new Check("质量标准", spec.isPresent(), spec.map(s -> s.specNo() + " V" + s.versionNo()).orElse("成品还没有生效的质量标准"), "/specs"));
        Long effId = eff.map(FormulaVersion::getId).orElse(null);
        boolean pilot = effId != null && trials.findByProjectIdOrderByTrialDateDescIdDesc(p.getId()).stream()
                .anyMatch(t -> t.getKind() == TrialKind.PILOT && t.getResult() == TrialResult.PASS && effId.equals(t.getFormulaVersionId()));
        out.add(new Check("中试通过", pilot, pilot ? "当前配方版本已有中试通过" : "要有一次按当前生效配方版本做的中试，结论为通过", null));
        return out;
    }

    /**
     * 包材设计稿：系统不知道哪些包材要印刷，所以要求生效配方里至少一个包材有现行设计稿，已批准的设计稿都不能是配方变了要重新评审的。
     * 没有设计稿的包材（瓶盖、泵头之类）不管。
     */
    private Check artworkCheck(FormulaVersion eff) {
        if (eff == null) {
            return new Check("包材设计稿", false, "先要有生效配方", "/artworks");
        }
        Map<Long, Item> items = lookup.itemMap();
        Set<Long> pks = master.versionLines(eff.getId()).stream().map(com.cosmetic.scm.master.BomLine::getItemId)
                .filter(id -> items.get(id).getItemType() == com.cosmetic.scm.domain.ItemType.PK).collect(Collectors.toSet());
        if (pks.isEmpty()) {
            return new Check("包材设计稿", false, "生效配方里没有包材", "/artworks");
        }
        List<ArtworkService.ArtworkView> approved = artworkService.viewAll(artworks.findByStatusInOrderById(List.of(com.cosmetic.scm.domain.ArtworkStatus.APPROVED)).stream()
                .filter(a -> pks.contains(a.getPkItemId())).toList());
        List<String> stale = approved.stream().filter(ArtworkService.ArtworkView::stale).map(ArtworkService.ArtworkView::pkName).toList();
        List<String> current = approved.stream().filter(a -> !a.stale()).map(a -> a.pkName() + " 第 " + a.versionNo() + " 版").toList();
        if (!stale.isEmpty()) {
            return new Check("包材设计稿", false, String.join("、", stale) + " 的设计稿要按新配方重新评审", "/artworks");
        }
        if (current.isEmpty()) {
            return new Check("包材设计稿", false, "配方里的包材还没有已批准的设计稿", "/artworks");
        }
        return new Check("包材设计稿", true, String.join("、", current) + " 已批准", "/artworks");
    }

    private void requireReady(NpdProject p) {
        List<String> missing = checks(p).stream().filter(c -> !c.ok()).map(c -> c.title() + "（" + c.detail() + "）").toList();
        if (!missing.isEmpty()) {
            throw new BizException("上市检查没通过：" + String.join("；", missing));
        }
    }

    private NpdProject lock(Long id) {
        return projects.lockById(id).orElseThrow(() -> BizException.notFound("新品项目"));
    }

    private static void requireStatus(NpdProject p, NpdStatus need, String action) {
        if (p.getStatus() != need) {
            throw new BizException("项目 " + p.getProjectNo() + " 当前为" + p.getStatus().getLabel() + "，不能" + action);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private List<ProjectRow> rows(List<NpdProject> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, List<NpdTrial>> byProject = trials.findByProjectIdIn(list.stream().map(NpdProject::getId).toList()).stream().collect(Collectors.groupingBy(NpdTrial::getProjectId));
        Map<Long, Item> itemMap = lookup.itemMap();
        LocalDate today = time.today();
        return list.stream().map(p -> {
            List<NpdTrial> mine = byProject.getOrDefault(p.getId(), List.of());
            Item fg = p.getFgItemId() == null ? null : itemMap.get(p.getFgItemId());
            boolean late = (p.getStatus() == NpdStatus.ACTIVE || p.getStatus() == NpdStatus.LAUNCH_REVIEW) && p.getTargetLaunch().isBefore(today);
            return new ProjectRow(p.getId(), p.getProjectNo(), p.getName(), p.getCategory(), p.getFilingType(), p.getFilingType().getLabel(), p.getClaims(), p.getTargetLaunch(), late,
                    p.getNote(), p.getFgItemId(), fg == null ? null : fg.getCode(), fg == null ? null : fg.getName(), p.getStatus(), p.getStatus().getLabel(), mine.size(),
                    mine.stream().anyMatch(t -> t.getKind() == TrialKind.PILOT && t.getResult() == TrialResult.PASS), p.getCreatedBy(), p.getCreatedAt(), p.getApprovedBy(),
                    p.getApprovedAt(), p.getRejectNote(), p.getSubmittedBy(), p.getLaunchedBy(), p.getLaunchedAt(), p.getCancelReason());
        }).toList();
    }

    private Detail detailOf(NpdProject p) {
        List<NpdTrial> list = trials.findByProjectIdOrderByTrialDateDescIdDesc(p.getId());
        Map<Long, Integer> versionNos = new HashMap<>();
        versions.findAllById(list.stream().map(NpdTrial::getFormulaVersionId).filter(java.util.Objects::nonNull).distinct().toList()).forEach(v -> versionNos.put(v.getId(), v.getVersionNo()));
        List<TrialView> tv = list.stream().map(t -> new TrialView(t.getId(), t.getTrialNo(), t.getKind(), t.getKind().getLabel(), t.getTrialDate(), t.getFormulaVersionId(),
                versionNos.get(t.getFormulaVersionId()), t.getBatchSize(), t.getResult(), t.getResult().getLabel(), t.getSummary(), t.getRecordedBy(), t.getRecordedAt())).toList();
        List<Check> checks = p.getStatus() == NpdStatus.PROPOSED || p.getStatus() == NpdStatus.REJECTED ? List.of() : checks(p);
        return new Detail(rows(List.of(p)).get(0), tv, checks, !checks.isEmpty() && checks.stream().allMatch(Check::ok));
    }
}
