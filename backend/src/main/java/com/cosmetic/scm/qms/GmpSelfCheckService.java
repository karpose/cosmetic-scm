package com.cosmetic.scm.qms;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.DocNoService;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.CapaSource;
import com.cosmetic.scm.domain.CapaType;
import com.cosmetic.scm.domain.CheckLevel;
import com.cosmetic.scm.domain.SelfCheckConclusion;
import com.cosmetic.scm.domain.SelfCheckResult;
import com.cosmetic.scm.domain.SelfCheckStatus;
import com.cosmetic.scm.domain.rules.GmpJudgement;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.CurrentUser;
import com.cosmetic.scm.security.SignatureService;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GMP 自查：按《化妆品生产质量管理规范检查要点（委托生产版）》24 项逐项检查。QA 或 QSO 发起、逐项登记，
 * 存在瑕疵或不符合的要写问题并转成 CAPA 才能提交；结论按判定原则自动算；另一位 QSO 签批。同一版本同时只能有一份在进行。
 * 写操作先锁自查记录（事务第一句）。规则见 docs/PLM-默认规则.md 的“GMP 自查”。
 */
@Service
@RequiredArgsConstructor
public class GmpSelfCheckService {

    /** 自查期间最长多少天（一个年度）。 */
    public static final int MAX_PERIOD_DAYS = 366;

    private final GmpSelfCheckRepository checks;
    private final GmpSelfCheckItemRepository items;
    private final GmpEvidenceService evidence;
    private final DeviationService deviations;
    private final CapaRepository capas;
    private final SignatureService signature;
    private final DocNoService docNo;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    public record CreateReq(@NotNull(message = "请选择自查期间的开始日期") LocalDate periodFrom, @NotNull(message = "请选择自查期间的结束日期") LocalDate periodTo,
                            @Size(max = 500) String scope) {
    }

    public record ItemReq(@NotNull(message = "请选择检查结果") SelfCheckResult result, @Size(max = 1000) String finding, @Size(max = 1000) String evidence) {
    }

    public record CapaReq(@NotNull(message = "请选择纠正还是预防") CapaType actionType, @NotBlank(message = "请填写措施内容") @Size(max = 1000) String description,
                          @NotBlank(message = "请填写负责人") @Size(max = 64) String owner, @NotNull(message = "请选择完成期限") LocalDate dueDate) {
    }

    public record SubmitReq(@NotBlank(message = "请填写自查总结") @Size(max = 1000) String summary) {
    }

    public record ApproveReq(@NotBlank(message = "请输入签批密码") String password) {
    }

    public record NoteReq(@NotBlank(message = "请写明原因") @Size(max = 300) String note) {
    }

    public record ItemView(int no, String clause, String category, CheckLevel level, String levelLabel, String title, String points, boolean autoCheck,
                           SelfCheckResult result, String resultLabel, String finding, String evidence, Long capaId, String capaNo, String capaStatusLabel,
                           String checkedBy, LocalDateTime checkedAt) {
    }

    /** rule2、rule3：判定原则第 2、3 条的计数，门槛 4 和 8。 */
    public record Counts(int total, int pending, int compliant, int flawed, int noncompliant, int notApplicable, int rule2, int rule3) {
    }

    public record CheckRow(Long id, String checkNo, String editionLabel, LocalDate periodFrom, LocalDate periodTo, String scope, SelfCheckStatus status, String statusLabel,
                           SelfCheckConclusion conclusion, String conclusionLabel, Counts counts, String createdBy, LocalDateTime createdAt, String submittedBy,
                           LocalDateTime submittedAt, String approvedBy, LocalDateTime approvedAt) {
    }

    public record CheckDetail(CheckRow check, String summary, String rejectNote, String cancelReason, List<ItemView> items) {
    }

    /** 还没结束（自查中、待签批）的显示按当前结果实时算的结论，结束的显示提交时定下的结论。 */
    @Transactional(readOnly = true)
    public List<CheckRow> list() {
        return rows(checks.findAllByOrderByIdDesc());
    }

    @Transactional(readOnly = true)
    public CheckDetail detail(Long id) {
        return detailOf(checks.findById(id).orElseThrow(() -> BizException.notFound("GMP 自查")));
    }

    @Transactional(readOnly = true)
    public List<GmpEvidenceService.Evidence> evidence(Long id) {
        GmpSelfCheck c = checks.findById(id).orElseThrow(() -> BizException.notFound("GMP 自查"));
        return evidence.evidence(c.getPeriodFrom(), c.getPeriodTo());
    }

    @Transactional(readOnly = true)
    public List<CheckRow> waitingApproval() {
        return rows(checks.findByStatusInOrderById(List.of(SelfCheckStatus.SUBMITTED)));
    }

    /** 最近一次签批的自查，总览按它算年度自查是否到期。 */
    @Transactional(readOnly = true)
    public Optional<GmpSelfCheck> lastApproved() {
        return checks.findFirstByStatusOrderByApprovedAtDesc(SelfCheckStatus.APPROVED);
    }

    @Transactional(readOnly = true)
    public boolean anyOpen() {
        return !checks.findByStatusInOrderById(List.of(SelfCheckStatus.DRAFT, SelfCheckStatus.SUBMITTED)).isEmpty();
    }

    @Transactional
    public CheckDetail create(CreateReq req) {
        // 先取单号：锁住当天“ZC”的流水行，而且是事务第一句，同时发起的会排队，之后的查询能看到刚提交的自查
        String checkNo = docNo.next("ZC", time.today());
        if (req.periodFrom().isAfter(req.periodTo())) {
            throw new BizException("自查期间的开始日期不能晚于结束日期");
        }
        if (req.periodTo().isAfter(time.today())) {
            throw new BizException("自查期间的结束日期不能晚于今天");
        }
        if (req.periodFrom().plusDays(MAX_PERIOD_DAYS).isBefore(req.periodTo().plusDays(1))) {
            throw new BizException("自查期间最长一年");
        }
        if (anyOpen()) {
            throw new BizException("已有一份自查在进行，签批或作废后再发起");
        }
        GmpSelfCheck c = new GmpSelfCheck();
        c.setCheckNo(checkNo);
        c.setEdition(GmpChecklist.EDITION);
        c.setPeriodFrom(req.periodFrom());
        c.setPeriodTo(req.periodTo());
        c.setScope(blankToNull(req.scope()));
        c.setStatus(SelfCheckStatus.DRAFT);
        c.setOpenKey(GmpChecklist.EDITION);
        c.setCreatedBy(currentUser.usernameOrSystem());
        c.setCreatedAt(time.now());
        try {
            // 唯一约束 open_key 兜底：两个人同时发起，后到的在这里失败
            checks.saveAndFlush(c);
        } catch (DataIntegrityViolationException e) {
            throw new BizException("已有一份自查在进行，签批或作废后再发起");
        }
        for (GmpChecklist.Item def : GmpChecklist.ITEMS) {
            GmpSelfCheckItem it = new GmpSelfCheckItem();
            it.setCheckId(c.getId());
            it.setItemNo(def.no());
            it.setResult(SelfCheckResult.PENDING);
            items.save(it);
        }
        audit.log("发起 GMP 自查", c.getCheckNo(), GmpChecklist.EDITION_LABEL + "，期间 " + c.getPeriodFrom() + " 至 " + c.getPeriodTo());
        return detailOf(c);
    }

    @Transactional
    public CheckDetail recordItem(Long id, int no, ItemReq req) {
        GmpSelfCheck c = lockDraft(id, "登记结果");
        GmpChecklist.Item def = definition(no);
        GmpSelfCheckItem it = items.findByCheckIdAndItemNo(c.getId(), no).orElseThrow();
        String finding = blankToNull(req.finding());
        switch (req.result()) {
            case PENDING -> throw new BizException("请选择检查结果");
            case FLAWED -> {
                if (def.level() == CheckLevel.GENERAL) {
                    throw new BizException("第 " + no + " 项是一般项目，没有“存在瑕疵”，请判符合或不符合");
                }
                if (finding == null) {
                    throw new BizException("第 " + no + " 项存在瑕疵，请写明发现的问题");
                }
            }
            case NONCOMPLIANT -> {
                if (finding == null) {
                    throw new BizException("第 " + no + " 项不符合，请写明发现的问题");
                }
            }
            case NOT_APPLICABLE -> {
                if (def.level() != CheckLevel.GENERAL) {
                    throw new BizException("第 " + no + " 项是" + def.level().getLabel() + "，不能判不适用");
                }
                if (finding == null) {
                    throw new BizException("第 " + no + " 项判不适用，请写明原因");
                }
            }
            default -> {
            }
        }
        it.setResult(req.result());
        it.setFinding(finding);
        it.setEvidence(blankToNull(req.evidence()));
        it.setCheckedBy(currentUser.usernameOrSystem());
        it.setCheckedAt(time.now());
        items.save(it);
        audit.log("登记 GMP 自查结果", c.getCheckNo(), "第 " + no + " 项 " + def.title() + "：" + req.result().getLabel(), finding);
        return detailOf(c);
    }

    /** 存在瑕疵或不符合的项目转成 CAPA，来源记“GMP 自查”。 */
    @Transactional
    public CheckDetail createCapa(Long id, int no, CapaReq req) {
        GmpSelfCheck c = lockDraft(id, "转 CAPA");
        GmpChecklist.Item def = definition(no);
        GmpSelfCheckItem it = items.findByCheckIdAndItemNo(c.getId(), no).orElseThrow();
        if (it.getResult() != SelfCheckResult.FLAWED && it.getResult() != SelfCheckResult.NONCOMPLIANT) {
            throw new BizException("第 " + no + " 项没有判存在瑕疵或不符合，不用转 CAPA");
        }
        if (it.getCapaId() != null) {
            throw new BizException("第 " + no + " 项已经转过 CAPA");
        }
        if (req.dueDate().isBefore(time.today())) {
            throw new BizException("完成期限不能早于今天");
        }
        DeviationService.CapaView capa = deviations.createCapa(new DeviationService.CapaReq(CapaSource.SELF_CHECK, c.getCheckNo() + " 第" + no + "项", null,
                req.actionType(), "GMP 自查第 " + no + " 项（" + def.clause() + " " + def.title() + "）：" + req.description().trim(), req.owner(), req.dueDate()));
        it.setCapaId(capa.id());
        items.save(it);
        audit.log("GMP 自查转 CAPA", c.getCheckNo(), "第 " + no + " 项 " + def.title() + "，CAPA " + capa.capaNo());
        return detailOf(c);
    }

    @Transactional
    public CheckDetail submit(Long id, SubmitReq req) {
        GmpSelfCheck c = lockDraft(id, "提交");
        List<GmpSelfCheckItem> list = items.findByCheckIdOrderByItemNo(c.getId());
        List<Integer> pending = list.stream().filter(i -> i.getResult() == SelfCheckResult.PENDING).map(GmpSelfCheckItem::getItemNo).toList();
        if (!pending.isEmpty()) {
            throw new BizException("还有 " + pending.size() + " 项没检查：第 " + join(pending) + " 项");
        }
        List<Integer> noCapa = list.stream().filter(i -> (i.getResult() == SelfCheckResult.FLAWED || i.getResult() == SelfCheckResult.NONCOMPLIANT) && i.getCapaId() == null)
                .map(GmpSelfCheckItem::getItemNo).toList();
        if (!noCapa.isEmpty()) {
            throw new BizException("第 " + join(noCapa) + " 项存在瑕疵或不符合，还没有转 CAPA");
        }
        SelfCheckConclusion conclusion = GmpJudgement.judge(lines(list));
        c.setStatus(SelfCheckStatus.SUBMITTED);
        c.setConclusion(conclusion);
        c.setSummary(req.summary().trim());
        c.setSubmittedBy(currentUser.usernameOrSystem());
        c.setSubmittedAt(time.now());
        c.setRejectNote(null);
        checks.save(c);
        Counts n = counts(list);
        audit.log("提交 GMP 自查", c.getCheckNo(), conclusion.getLabel() + "：符合 " + n.compliant() + "，存在瑕疵 " + n.flawed() + "，不符合 " + n.noncompliant()
                + "，不适用 " + n.notApplicable(), c.getSummary());
        return detailOf(c);
    }

    @Transactional
    public CheckDetail approve(Long id, ApproveReq req) {
        GmpSelfCheck c = lock(id);
        requireStatus(c, SelfCheckStatus.SUBMITTED, "签批");
        AppUser signer = signature.verify(req.password());
        if (signer.getUsername().equals(c.getSubmittedBy())) {
            throw new BizException("签批人不能是提交人");
        }
        c.setStatus(SelfCheckStatus.APPROVED);
        c.setOpenKey(null);
        c.setApprovedBy(signer.getUsername());
        c.setApprovedAt(time.now());
        checks.save(c);
        audit.log("签批 GMP 自查", c.getCheckNo(), "结论：" + c.getConclusion().getLabel());
        return detailOf(c);
    }

    @Transactional
    public CheckDetail reject(Long id, NoteReq req) {
        GmpSelfCheck c = lock(id);
        requireStatus(c, SelfCheckStatus.SUBMITTED, "退回");
        c.setStatus(SelfCheckStatus.DRAFT);
        c.setConclusion(null);
        c.setRejectNote(req.note().trim());
        checks.save(c);
        audit.log("退回 GMP 自查", c.getCheckNo(), "退回重新检查", req.note().trim());
        return detailOf(c);
    }

    /** 作废：已登记的结果和转出的 CAPA 都保留。 */
    @Transactional
    public CheckDetail cancel(Long id, NoteReq req) {
        GmpSelfCheck c = lock(id);
        if (c.getStatus() != SelfCheckStatus.DRAFT && c.getStatus() != SelfCheckStatus.SUBMITTED) {
            throw new BizException("自查 " + c.getCheckNo() + " 当前为" + c.getStatus().getLabel() + "，不能作废");
        }
        c.setStatus(SelfCheckStatus.CANCELLED);
        c.setOpenKey(null);
        c.setCancelReason(req.note().trim());
        checks.save(c);
        audit.log("作废 GMP 自查", c.getCheckNo(), "已登记的结果和 CAPA 保留", req.note().trim());
        return detailOf(c);
    }

    private GmpSelfCheck lock(Long id) {
        return checks.lockById(id).orElseThrow(() -> BizException.notFound("GMP 自查"));
    }

    private GmpSelfCheck lockDraft(Long id, String action) {
        GmpSelfCheck c = lock(id);
        requireStatus(c, SelfCheckStatus.DRAFT, action);
        return c;
    }

    private static void requireStatus(GmpSelfCheck c, SelfCheckStatus need, String action) {
        if (c.getStatus() != need) {
            throw new BizException("自查 " + c.getCheckNo() + " 当前为" + c.getStatus().getLabel() + "，不能" + action);
        }
    }

    private static GmpChecklist.Item definition(int no) {
        if (no < 1 || no > GmpChecklist.ITEMS.size()) {
            throw BizException.notFound("检查项目");
        }
        return GmpChecklist.item(no);
    }

    private static List<GmpJudgement.Line> lines(List<GmpSelfCheckItem> list) {
        return list.stream().map(i -> new GmpJudgement.Line(GmpChecklist.item(i.getItemNo()).level(), i.getResult())).toList();
    }

    private static Counts counts(List<GmpSelfCheckItem> list) {
        int[] n = new int[5];
        for (GmpSelfCheckItem i : list) {
            switch (i.getResult()) {
                case PENDING -> n[0]++;
                case COMPLIANT -> n[1]++;
                case FLAWED -> n[2]++;
                case NONCOMPLIANT -> n[3]++;
                case NOT_APPLICABLE -> n[4]++;
                default -> throw new IllegalStateException();
            }
        }
        GmpJudgement.Counts j = GmpJudgement.count(lines(list));
        return new Counts(list.size(), n[0], n[1], n[2], n[3], n[4], j.rule2(), j.rule3());
    }

    /** 一次查完所有自查的明细再分组，不在循环里查库。 */
    private List<CheckRow> rows(List<GmpSelfCheck> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, List<GmpSelfCheckItem>> byCheck = items.findByCheckIdInOrderByItemNo(list.stream().map(GmpSelfCheck::getId).toList()).stream()
                .collect(java.util.stream.Collectors.groupingBy(GmpSelfCheckItem::getCheckId));
        return list.stream().map(c -> row(c, byCheck.getOrDefault(c.getId(), List.of()))).toList();
    }

    private CheckRow row(GmpSelfCheck c, List<GmpSelfCheckItem> list) {
        boolean open = c.getStatus() == SelfCheckStatus.DRAFT;
        SelfCheckConclusion concl = open ? GmpJudgement.judge(lines(list)) : c.getConclusion();
        return new CheckRow(c.getId(), c.getCheckNo(), GmpChecklist.EDITION_LABEL, c.getPeriodFrom(), c.getPeriodTo(), c.getScope(), c.getStatus(), c.getStatus().getLabel(),
                concl, concl == null ? null : concl.getLabel(), counts(list), c.getCreatedBy(), c.getCreatedAt(), c.getSubmittedBy(), c.getSubmittedAt(),
                c.getApprovedBy(), c.getApprovedAt());
    }

    private CheckDetail detailOf(GmpSelfCheck c) {
        List<GmpSelfCheckItem> list = items.findByCheckIdOrderByItemNo(c.getId());
        Map<Long, Capa> capaMap = new HashMap<>();
        capas.findAllById(list.stream().map(GmpSelfCheckItem::getCapaId).filter(java.util.Objects::nonNull).toList()).forEach(x -> capaMap.put(x.getId(), x));
        List<ItemView> views = new ArrayList<>();
        for (GmpSelfCheckItem i : list) {
            GmpChecklist.Item d = GmpChecklist.item(i.getItemNo());
            Capa capa = i.getCapaId() == null ? null : capaMap.get(i.getCapaId());
            views.add(new ItemView(d.no(), d.clause(), d.category(), d.level(), d.level().getLabel(), d.title(), d.points(), d.autoCheck(), i.getResult(),
                    i.getResult().getLabel(), i.getFinding(), i.getEvidence(), i.getCapaId(), capa == null ? null : capa.getCapaNo(),
                    capa == null ? null : capa.getStatus().getLabel(), i.getCheckedBy(), i.getCheckedAt()));
        }
        return new CheckDetail(row(c, list), c.getSummary(), c.getRejectNote(), c.getCancelReason(), views);
    }

    private static String join(List<Integer> nos) {
        return String.join("、", nos.stream().map(String::valueOf).toList());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
