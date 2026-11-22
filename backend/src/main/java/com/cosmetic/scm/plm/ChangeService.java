package com.cosmetic.scm.plm;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.DocNoService;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.ChangeStatus;
import com.cosmetic.scm.domain.ChangeType;
import com.cosmetic.scm.domain.RiskLevel;
import com.cosmetic.scm.domain.Role;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.CurrentUser;
import com.cosmetic.scm.security.SignatureService;
import com.cosmetic.scm.security.UserRepository;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 变更控制：申请 → QA/QSO 评估（风险、影响、要做的行动）→ QSO 批准（不能是申请人）→ 执行行动 → 全部完成后 QSO 关闭（不能是申请人）。
 * 评估时勾选的常见后续工作会自动生成行动项。系统不判断评估内容对不对。规则都是默认值，见 docs/PLM-默认规则.md。
 */
@Service
@RequiredArgsConstructor
public class ChangeService {

    /** 自动生成的行动项默认期限。 */
    public static final int DEFAULT_ACTION_DAYS = 30;

    private final ChangeRequestRepository changes;
    private final ChangeActionRepository actions;
    private final UserRepository users;
    private final FormulaVersionRepository formulaVersions;
    private final LabelArtworkRepository labelArtworks;
    private final com.cosmetic.scm.master.MasterLookup lookup;
    @org.springframework.beans.factory.annotation.Value("${scm.change.require-for-formula:false}")
    private boolean requireForFormula;

    @org.springframework.beans.factory.annotation.Value("${scm.change.require-for-artwork:false}")
    private boolean requireForArtwork;
    private final SignatureService signature;
    private final DocNoService docNo;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    public record CreateReq(@NotBlank(message = "请填写变更标题") @Size(max = 120) String title, @NotNull(message = "请选择变更类型") ChangeType changeType,
                            @NotBlank(message = "请写明变更原因") @Size(max = 500) String reason, @NotBlank(message = "请写明变更内容") @Size(max = 1000) String description,
                            @Size(max = 300) String scope) {
    }

    public record ActionReq(@NotBlank(message = "请填写行动内容") @Size(max = 300) String content, @NotBlank(message = "请填写负责人") @Size(max = 64) String owner,
                            @NotNull(message = "请选择完成期限") LocalDate dueDate) {
    }

    public record AssessReq(@NotNull(message = "请选择风险等级") RiskLevel risk, @NotBlank(message = "请填写影响评估") @Size(max = 1000) String impact,
                            boolean needFiling, boolean needStability, boolean needLabel, boolean needValidation, List<@Valid ActionReq> actions) {
    }

    public record ApproveReq(@NotBlank(message = "请输入签批密码") String password) {
    }

    public record CloseReq(@NotBlank(message = "请填写效果确认") @Size(max = 500) String note, @NotBlank(message = "请输入签批密码") String password) {
    }

    public record NoteReq(@NotBlank(message = "请写明原因或情况") @Size(max = 300) String note) {
    }

    public record ActionView(Long id, int lineNo, String content, String owner, LocalDate dueDate, boolean overdue, boolean done, String doneNote, String doneBy, LocalDateTime doneAt) {
    }

    public record ChangeRow(Long id, String changeNo, String title, ChangeType changeType, String changeTypeLabel, String reason, String description, String scope,
                            ChangeStatus status, String statusLabel, RiskLevel risk, String riskLabel, String impact, int actionsTotal, int actionsOpen, int actionsOverdue,
                            String createdBy, LocalDateTime createdAt, String assessedBy, String approvedBy, String rejectNote, String closeNote, String closedBy, LocalDateTime closedAt) {
    }

    public record LinkedFormula(Long versionId, String fgCode, String fgName, int versionNo, String status, String statusLabel) {
    }

    public record LinkedArtwork(Long artworkId, String artworkNo, String pkCode, String pkName, int versionNo, String status, String statusLabel) {
    }

    public record ChangeDetail(ChangeRow change, List<ActionView> actions, List<LinkedFormula> formulas, List<LinkedArtwork> artworks) {
    }

    public record Option(Long id, String changeNo, String title, String changeTypeLabel) {
    }

    /** 发起配方变更时可选的变更：实施中的配方或原料变更。required 为 true 时必须选。 */
    public record FormulaOptions(boolean required, List<Option> changes) {
    }

    @Transactional(readOnly = true)
    public FormulaOptions formulaOptions() {
        return new FormulaOptions(requireForFormula, changes.findByStatusInOrderById(List.of(ChangeStatus.APPROVED)).stream()
                .filter(c -> c.getChangeType() == ChangeType.FORMULA || c.getChangeType() == ChangeType.MATERIAL)
                .map(c -> new Option(c.getId(), c.getChangeNo(), c.getTitle(), c.getChangeType().getLabel())).toList());
    }

    /** 提交设计稿时可选的变更：实施中的标签、包材或配方变更。required 为 true 时改版必须选。 */
    @Transactional(readOnly = true)
    public FormulaOptions artworkOptions() {
        return new FormulaOptions(requireForArtwork, changes.findByStatusInOrderById(List.of(ChangeStatus.APPROVED)).stream()
                .filter(c -> ArtworkService.LINKABLE.contains(c.getChangeType()))
                .map(c -> new Option(c.getId(), c.getChangeNo(), c.getTitle(), c.getChangeType().getLabel())).toList());
    }

    public record TypeCount(String type, String label, long count, long openCount, long highRiskCount) {
    }

    public record ChangeSummary(long totalChanges, long openChanges, long submittedCount, long assessedCount,
                                long approvedCount, long closedCount, long highRiskOpenCount,
                                long totalActions, long doneActions, long overdueActions,
                                Double actionCompleteRatePct, Double closeRatePct,
                                List<TypeCount> byType) {
    }

    public record ChangeClosureRow(Long changeId, String changeNo, String title, String changeTypeLabel,
                                   String statusLabel, String riskLabel, String createdBy,
                                   int actionsTotal, int actionsOpen, int actionsOverdue,
                                   String closureState, String closureLabel, String recommendedAction) {
    }

    public record ChangeClosureBoard(int totalCount, int actionOverdueCount, int readyToCloseCount,
                                     int pendingApproveCount, int pendingAssessCount,
                                     List<ChangeClosureRow> rows) {
    }

    @Transactional(readOnly = true)
    public List<ChangeRow> list() {
        return rows(changes.findTop300ByOrderByIdDesc());
    }

    @Transactional(readOnly = true)
    public List<ChangeRow> listAll() {
        return rows(changes.findAllByOrderByIdDesc());
    }

    @Transactional(readOnly = true)
    public ChangeSummary summary() {
        List<ChangeRow> all = listAll();
        long total = all.size();
        long submitted = all.stream().filter(c -> c.status() == ChangeStatus.SUBMITTED).count();
        long assessed = all.stream().filter(c -> c.status() == ChangeStatus.ASSESSED).count();
        long approved = all.stream().filter(c -> c.status() == ChangeStatus.APPROVED).count();
        long closed = all.stream().filter(c -> c.status() == ChangeStatus.CLOSED).count();
        long open = submitted + assessed + approved;
        long highRiskOpen = all.stream().filter(c -> (c.status() == ChangeStatus.SUBMITTED || c.status() == ChangeStatus.ASSESSED || c.status() == ChangeStatus.APPROVED)
                && c.risk() == RiskLevel.HIGH).count();
        long totalActions = all.stream().mapToLong(ChangeRow::actionsTotal).sum();
        long openActions = all.stream().mapToLong(ChangeRow::actionsOpen).sum();
        long doneActions = totalActions - openActions;
        long overdueActions = all.stream().mapToLong(ChangeRow::actionsOverdue).sum();
        Double actionRate = totalActions == 0 ? null : Math.round(doneActions * 1000.0 / totalActions) / 10.0;
        long effectiveTotal = open + closed;
        Double closeRate = effectiveTotal == 0 ? null : Math.round(closed * 1000.0 / effectiveTotal) / 10.0;
        Map<ChangeType, List<ChangeRow>> grouped = all.stream().collect(Collectors.groupingBy(ChangeRow::changeType, java.util.LinkedHashMap::new, Collectors.toList()));
        List<TypeCount> byType = grouped.entrySet().stream()
                .map(e -> {
                    List<ChangeRow> list = e.getValue();
                    long op = list.stream().filter(c -> c.status() == ChangeStatus.SUBMITTED || c.status() == ChangeStatus.ASSESSED || c.status() == ChangeStatus.APPROVED).count();
                    long hr = list.stream().filter(c -> c.risk() == RiskLevel.HIGH).count();
                    return new TypeCount(e.getKey().name(), e.getKey().getLabel(), list.size(), op, hr);
                })
                .sorted(java.util.Comparator.comparingLong(TypeCount::count).reversed())
                .toList();
        return new ChangeSummary(total, open, submitted, assessed, approved, closed, highRiskOpen,
                totalActions, doneActions, overdueActions, actionRate, closeRate, byType);
    }

    @Transactional(readOnly = true)
    public ChangeDetail detail(Long id) {
        return detailOf(changes.findById(id).orElseThrow(() -> BizException.notFound("变更")));
    }

    @Transactional(readOnly = true)
    public List<ChangeRow> byStatus(ChangeStatus status) {
        return rows(changes.findByStatusInOrderById(List.of(status)));
    }

    @Transactional(readOnly = true)
    public List<ActionView> overdueActions() {
        return actions.findOverdue(time.today()).stream().map(this::view).toList();
    }

    /** 变更控制与行动超期闭环监控看板（V72）。 */
    @Transactional(readOnly = true)
    public ChangeClosureBoard closureBoard() {
        List<ChangeRow> open = rows(changes.findByStatusInOrderById(
                List.of(ChangeStatus.SUBMITTED, ChangeStatus.ASSESSED, ChangeStatus.APPROVED)));
        List<ChangeClosureRow> items = new ArrayList<>();
        int overdueCnt = 0;
        int readyCnt = 0;
        int approveCnt = 0;
        int assessCnt = 0;

        for (ChangeRow c : open) {
            String state;
            String label;
            String action;
            if (c.status() == ChangeStatus.APPROVED && c.actionsOverdue() > 0) {
                state = "ACTION_OVERDUE";
                label = "行动已超期";
                action = "有 " + c.actionsOverdue() + " 项行动已超期（共 " + c.actionsOpen() + " 项未完成），请督办负责人立即整改闭环";
                overdueCnt++;
            } else if (c.status() == ChangeStatus.APPROVED && c.actionsOpen() == 0) {
                state = "READY_TO_CLOSE";
                label = "待 QSO 确认关闭";
                action = "全部行动项已完成，请质量安全负责人确认变更效果并签批关闭";
                readyCnt++;
            } else if (c.status() == ChangeStatus.ASSESSED) {
                state = "PENDING_APPROVE";
                label = c.risk() == RiskLevel.HIGH ? "高风险待批" : "待 QSO 批准";
                action = "评估已完成（风险" + c.riskLabel() + "，含 " + c.actionsTotal() + " 项行动），请质量安全负责人审批";
                approveCnt++;
            } else if (c.status() == ChangeStatus.SUBMITTED) {
                state = "PENDING_ASSESS";
                label = "待 QA 评估";
                action = "变更已提交，请质量人员开展风险与影响评估并制定行动项";
                assessCnt++;
            } else {
                state = "IN_PROGRESS";
                label = "实施跟进中";
                action = "变更实施中，剩余 " + c.actionsOpen() + " 项行动按计划推进";
            }
            items.add(new ChangeClosureRow(
                    c.id(), c.changeNo(), c.title(), c.changeTypeLabel(), c.statusLabel(),
                    c.riskLabel() == null ? "未评估" : c.riskLabel(), c.createdBy(),
                    c.actionsTotal(), c.actionsOpen(), c.actionsOverdue(), state, label, action));
        }

        items.sort(java.util.Comparator.comparingInt((ChangeClosureRow r) -> closureRank(r.closureState()))
                .thenComparing(ChangeClosureRow::actionsOverdue, java.util.Comparator.reverseOrder())
                .thenComparing(ChangeClosureRow::changeNo));

        return new ChangeClosureBoard(items.size(), overdueCnt, readyCnt, approveCnt, assessCnt, items);
    }

    private static int closureRank(String state) {
        return switch (state) {
            case "ACTION_OVERDUE" -> 0;
            case "READY_TO_CLOSE" -> 1;
            case "PENDING_APPROVE" -> 2;
            case "PENDING_ASSESS" -> 3;
            default -> 4;
        };
    }

    @Transactional
    public ChangeDetail create(CreateReq req) {
        ChangeRequest c = new ChangeRequest();
        c.setChangeNo(docNo.next("CC", time.today()));
        c.setTitle(req.title().trim());
        c.setChangeType(req.changeType());
        c.setReason(req.reason().trim());
        c.setDescription(req.description().trim());
        c.setScope(blankToNull(req.scope()));
        c.setStatus(ChangeStatus.SUBMITTED);
        c.setCreatedBy(currentUser.usernameOrSystem());
        c.setCreatedAt(time.now());
        changes.save(c);
        audit.log("提出变更", c.getChangeNo(), req.changeType().getLabel() + "：" + c.getTitle(), c.getReason());
        return detailOf(c);
    }

    @Transactional
    public ChangeDetail assess(Long id, AssessReq req) {
        ChangeRequest c = lock(id);
        requireStatus(c, ChangeStatus.SUBMITTED, "评估");
        String me = currentUser.usernameOrSystem();
        LocalDate due = time.today().plusDays(DEFAULT_ACTION_DAYS);
        List<ActionReq> list = new ArrayList<>();
        if (req.needFiling()) {
            list.add(new ActionReq("变更备案或注册资料", me, due));
        }
        if (req.needStability()) {
            list.add(new ActionReq("补做稳定性试验", me, due));
        }
        if (req.needLabel()) {
            list.add(new ActionReq("更新标签并重新签批", me, due));
        }
        if (req.needValidation()) {
            list.add(new ActionReq("完成验证并留存记录", me, due));
        }
        if (req.actions() != null) {
            list.addAll(req.actions());
        }
        int n = actions.findByChangeIdOrderByLineNo(c.getId()).size();
        for (ActionReq a : list) {
            addAction(c, ++n, a);
        }
        c.setStatus(ChangeStatus.ASSESSED);
        c.setRisk(req.risk());
        c.setImpact(req.impact().trim());
        c.setAssessedBy(me);
        c.setAssessedAt(time.now());
        changes.save(c);
        audit.log("评估变更", c.getChangeNo(), "风险" + req.risk().getLabel() + "，" + list.size() + " 项行动：" + c.getImpact());
        return detailOf(c);
    }

    @Transactional
    public ChangeDetail approve(Long id, ApproveReq req) {
        ChangeRequest c = lock(id);
        requireStatus(c, ChangeStatus.ASSESSED, "批准");
        AppUser signer = signature.verify(req.password());
        if (signer.getUsername().equals(c.getCreatedBy())) {
            throw new BizException("批准人不能是申请人");
        }
        c.setStatus(ChangeStatus.APPROVED);
        c.setApprovedBy(signer.getUsername());
        c.setApprovedAt(time.now());
        changes.save(c);
        audit.log("批准变更", c.getChangeNo(), c.getTitle());
        return detailOf(c);
    }

    @Transactional
    public ChangeDetail reject(Long id, NoteReq req) {
        ChangeRequest c = lock(id);
        if (c.getStatus() != ChangeStatus.SUBMITTED && c.getStatus() != ChangeStatus.ASSESSED) {
            throw new BizException("变更 " + c.getChangeNo() + " 当前为" + c.getStatus().getLabel() + "，不能不批准");
        }
        c.setStatus(ChangeStatus.REJECTED);
        c.setRejectNote(req.note().trim());
        changes.save(c);
        audit.log("不批准变更", c.getChangeNo(), c.getTitle(), req.note().trim());
        return detailOf(c);
    }

    /** 批准后实施中发现还要做的事，可以加行动项。 */
    @Transactional
    public ChangeDetail addAction(Long id, ActionReq req) {
        ChangeRequest c = lock(id);
        requireStatus(c, ChangeStatus.APPROVED, "加行动项");
        ChangeAction a = addAction(c, actions.findByChangeIdOrderByLineNo(c.getId()).size() + 1, req);
        audit.log("变更加行动项", c.getChangeNo(), a.getContent() + "，负责人 " + a.getOwner() + "，" + a.getDueDate() + " 前");
        return detailOf(c);
    }

    /** 完成行动：负责人本人或 QA/QSO。先锁变更（路径里带变更 id）。 */
    @Transactional
    public ChangeDetail completeAction(Long id, Long actionId, NoteReq req) {
        ChangeRequest c = lock(id);
        requireStatus(c, ChangeStatus.APPROVED, "完成行动");
        ChangeAction a = actions.findById(actionId).filter(x -> x.getChangeId().equals(c.getId())).orElseThrow(() -> BizException.notFound("行动项"));
        if (a.isDone()) {
            throw new BizException("这项行动已经完成了");
        }
        String me = currentUser.usernameOrSystem();
        boolean quality = currentUser.find().map(u -> u.hasRole(Role.QA) || u.hasRole(Role.QSO)).orElse(false);
        if (!me.equals(a.getOwner()) && !quality) {
            throw new BizException("只有负责人 " + a.getOwner() + " 或质量人员能登记完成");
        }
        a.setDone(true);
        a.setDoneNote(req.note().trim());
        a.setDoneBy(me);
        a.setDoneAt(time.now());
        actions.save(a);
        audit.log("完成变更行动", c.getChangeNo(), a.getContent() + "：" + a.getDoneNote());
        return detailOf(c);
    }

    @Transactional
    public ChangeDetail close(Long id, CloseReq req) {
        ChangeRequest c = lock(id);
        requireStatus(c, ChangeStatus.APPROVED, "关闭");
        AppUser signer = signature.verify(req.password());
        if (signer.getUsername().equals(c.getCreatedBy())) {
            throw new BizException("关闭人不能是申请人");
        }
        List<String> open = actions.findByChangeIdOrderByLineNo(c.getId()).stream().filter(a -> !a.isDone()).map(ChangeAction::getContent).toList();
        if (!open.isEmpty()) {
            throw new BizException("还有行动没完成：" + String.join("、", open));
        }
        // 关联的配方还在草稿或待签批，说明变更还没落地
        List<String> pendingFormulas = formulaVersions.findByChangeIdOrderById(c.getId()).stream()
                .filter(v -> v.getStatus() == com.cosmetic.scm.domain.FormulaStatus.DRAFT || v.getStatus() == com.cosmetic.scm.domain.FormulaStatus.PENDING)
                .map(v -> lookup.item(v.getFgItemId()).getCode() + " V" + v.getVersionNo()).toList();
        if (!pendingFormulas.isEmpty()) {
            throw new BizException("关联的配方 " + String.join("、", pendingFormulas) + " 还没签批或放弃，不能关闭");
        }
        List<String> pendingArtworks = labelArtworks.findByChangeIdOrderById(c.getId()).stream().filter(a -> a.getStatus() == com.cosmetic.scm.domain.ArtworkStatus.PENDING)
                .map(LabelArtwork::getArtworkNo).toList();
        if (!pendingArtworks.isEmpty()) {
            throw new BizException("关联的设计稿 " + String.join("、", pendingArtworks) + " 还没批准或退回，不能关闭");
        }
        c.setStatus(ChangeStatus.CLOSED);
        c.setCloseNote(req.note().trim());
        c.setClosedBy(signer.getUsername());
        c.setClosedAt(time.now());
        changes.save(c);
        audit.log("关闭变更", c.getChangeNo(), c.getTitle() + "：" + c.getCloseNote());
        return detailOf(c);
    }

    /** 批准前申请人或质量人员可以取消。 */
    @Transactional
    public ChangeDetail cancel(Long id, NoteReq req) {
        ChangeRequest c = lock(id);
        if (c.getStatus() != ChangeStatus.SUBMITTED && c.getStatus() != ChangeStatus.ASSESSED) {
            throw new BizException("变更 " + c.getChangeNo() + " 当前为" + c.getStatus().getLabel() + "，不能取消");
        }
        String me = currentUser.usernameOrSystem();
        boolean quality = currentUser.find().map(u -> u.hasRole(Role.QA) || u.hasRole(Role.QSO)).orElse(false);
        if (!me.equals(c.getCreatedBy()) && !quality) {
            throw new BizException("只有申请人或质量人员能取消");
        }
        c.setStatus(ChangeStatus.CANCELLED);
        c.setRejectNote(req.note().trim());
        changes.save(c);
        audit.log("取消变更", c.getChangeNo(), c.getTitle(), req.note().trim());
        return detailOf(c);
    }

    private ChangeAction addAction(ChangeRequest c, int lineNo, ActionReq req) {
        String owner = req.owner().trim();
        if (!users.existsByUsername(owner)) {
            throw new BizException("负责人账号 " + owner + " 不存在");
        }
        if (req.dueDate().isBefore(time.today())) {
            throw new BizException("完成期限不能早于今天");
        }
        ChangeAction a = new ChangeAction();
        a.setChangeId(c.getId());
        a.setLineNo(lineNo);
        a.setContent(req.content().trim());
        a.setOwner(owner);
        a.setDueDate(req.dueDate());
        a.setDone(false);
        return actions.save(a);
    }

    private ChangeRequest lock(Long id) {
        return changes.lockById(id).orElseThrow(() -> BizException.notFound("变更"));
    }

    private static void requireStatus(ChangeRequest c, ChangeStatus need, String action) {
        if (c.getStatus() != need) {
            throw new BizException("变更 " + c.getChangeNo() + " 当前为" + c.getStatus().getLabel() + "，不能" + action);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private ActionView view(ChangeAction a) {
        return new ActionView(a.getId(), a.getLineNo(), a.getContent(), a.getOwner(), a.getDueDate(), !a.isDone() && a.getDueDate().isBefore(time.today()), a.isDone(),
                a.getDoneNote(), a.getDoneBy(), a.getDoneAt());
    }

    private List<ChangeRow> rows(List<ChangeRequest> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, List<ChangeAction>> byChange = actions.findByChangeIdIn(list.stream().map(ChangeRequest::getId).toList()).stream().collect(Collectors.groupingBy(ChangeAction::getChangeId));
        LocalDate today = time.today();
        return list.stream().map(c -> {
            List<ChangeAction> mine = byChange.getOrDefault(c.getId(), List.of());
            int open = (int) mine.stream().filter(a -> !a.isDone()).count();
            int overdue = c.getStatus() == ChangeStatus.APPROVED ? (int) mine.stream().filter(a -> !a.isDone() && a.getDueDate().isBefore(today)).count() : 0;
            return new ChangeRow(c.getId(), c.getChangeNo(), c.getTitle(), c.getChangeType(), c.getChangeType().getLabel(), c.getReason(), c.getDescription(), c.getScope(),
                    c.getStatus(), c.getStatus().getLabel(), c.getRisk(), c.getRisk() == null ? null : c.getRisk().getLabel(), c.getImpact(), mine.size(), open, overdue,
                    c.getCreatedBy(), c.getCreatedAt(), c.getAssessedBy(), c.getApprovedBy(), c.getRejectNote(), c.getCloseNote(), c.getClosedBy(), c.getClosedAt());
        }).toList();
    }

    private ChangeDetail detailOf(ChangeRequest c) {
        List<LinkedFormula> formulas = formulaVersions.findByChangeIdOrderById(c.getId()).stream().map(v -> {
            var fg = lookup.item(v.getFgItemId());
            return new LinkedFormula(v.getId(), fg.getCode(), fg.getName(), v.getVersionNo(), v.getStatus().name(), v.getStatus().getLabel());
        }).toList();
        List<LinkedArtwork> arts = labelArtworks.findByChangeIdOrderById(c.getId()).stream().map(a -> {
            var pk = lookup.item(a.getPkItemId());
            return new LinkedArtwork(a.getId(), a.getArtworkNo(), pk.getCode(), pk.getName(), a.getVersionNo(), a.getStatus().name(), a.getStatus().getLabel());
        }).toList();
        return new ChangeDetail(rows(List.of(c)).get(0), actions.findByChangeIdOrderByLineNo(c.getId()).stream().map(this::view).toList(), formulas, arts);
    }
}
