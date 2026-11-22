package com.cosmetic.scm.plm;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.cosmetic.scm.attachment.AttachmentRepository;
import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.DocCategory;
import com.cosmetic.scm.domain.DocCopyStatus;
import com.cosmetic.scm.domain.DocStatus;
import com.cosmetic.scm.domain.OwnerType;
import com.cosmetic.scm.domain.RevisionStatus;
import com.cosmetic.scm.domain.Role;
import com.cosmetic.scm.security.UserRepository;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.CurrentUser;
import com.cosmetic.scm.security.SignatureService;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 受控文件：每次修订是一个新版本，带文件附件，QSO 签批（不能是起草人）。现行版本是已签批、已到生效日期的最新一版。
 * 按复审周期提醒，复审确认仍有效或出新版都算复审过。规则都是默认值，见 docs/PLM-默认规则.md。
 */
@Service
@RequiredArgsConstructor
public class DocControlService {

    /** 现行版本生效后多少天内要确认完，超过算超期。 */
    public static final int ACK_DAYS = 14;

    /** 复审到期前多少天开始提醒。 */
    public static final int REVIEW_WARN_DAYS = 30;

    /** 新版生效或文件作废后，旧版纸质副本要在多少天内收回，超过算超期。 */
    public static final int RETRIEVE_DAYS = 7;

    /** 一次最多发几份。 */
    public static final int MAX_COPIES_PER_ISSUE = 20;

    private final ControlledDocRepository docs;
    private final DocRevisionRepository revisions;
    private final AttachmentRepository attachments;
    private final DocAckRepository acks;
    private final DocCopyRepository copies;
    private final UserRepository users;
    private final SignatureService signature;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    public record CreateReq(@NotBlank(message = "请填写文件编号") @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9._-]+", message = "文件编号只能用字母、数字和 . _ -") String docCode,
                            @NotBlank(message = "请填写文件名称") @Size(max = 120) String title, @NotNull(message = "请选择类别") DocCategory category,
                            @Size(max = 60) String ownerDept, @Min(value = 1, message = "复审周期至少 1 个月") @Max(value = 60, message = "复审周期最多 60 个月") Integer reviewMonths,
                            @NotBlank(message = "请写明制定或修订内容") @Size(max = 300) String changeSummary, LocalDate effectiveDate, java.util.Set<Role> trainRoles) {
        public CreateReq(String docCode, String title, DocCategory category, String ownerDept, Integer reviewMonths, String changeSummary, LocalDate effectiveDate) {
            this(docCode, title, category, ownerDept, reviewMonths, changeSummary, effectiveDate, null);
        }
    }

    public record ReviseReq(@NotBlank(message = "请写明修订内容") @Size(max = 300) String changeSummary, LocalDate effectiveDate) {
    }

    public record ApproveReq(@NotBlank(message = "请输入签批密码") String password) {
    }

    public record NoteReq(@NotBlank(message = "请写明原因") @Size(max = 300) String note) {
    }

    public record RolesReq(java.util.Set<Role> trainRoles) {
    }

    public record AckView(String username, String displayName, LocalDateTime ackedAt) {
    }

    /** 要确认的那一版（现行版，没有就是待生效版）和每个应确认的人。 */
    public record AckStatus(Long revisionId, int revNo, List<AckView> people, int done, int total) {
    }

    public record MyAck(Long docId, String docCode, String title, Long revisionId, int revNo, LocalDate effectiveDate) {
    }

    public record RevisionView(Long id, int revNo, String changeSummary, LocalDate effectiveDate, RevisionStatus status, String statusLabel, boolean current, boolean upcoming,
                               String rejectNote, String draftedBy, LocalDateTime draftedAt, String approvedBy, LocalDateTime approvedAt) {
    }

    /** copiesOut：在用的纸质副本；copiesToRetrieve：其中已经不是现行版本（或文件已作废）要收回的；retrieveDue：最早的回收期限。 */
    public record DocRow(Long id, String docCode, String title, DocCategory category, String categoryLabel, String ownerDept, int reviewMonths, DocStatus status, String statusLabel,
                         Integer currentRev, LocalDate currentEffective, Long pendingRevisionId, Integer upcomingRev, LocalDate upcomingEffective,
                         LocalDate lastReviewedOn, LocalDate reviewDue, boolean reviewOverdue, String obsoleteReason,
                         List<Role> trainRoles, int ackPending, boolean ackOverdue, int copiesOut, int copiesToRetrieve, LocalDate retrieveDue, boolean retrieveOverdue) {
    }

    /** mustRetrieve：在用但已不是现行版本（或文件已作废），要收回；retrieveDue：收回期限。 */
    public record CopyView(Long id, Long revisionId, int revNo, int copyNo, String holder, DocCopyStatus status, String statusLabel, String issuedBy, LocalDateTime issuedAt,
                           String closedBy, LocalDateTime closedAt, String closeNote, boolean mustRetrieve, LocalDate retrieveDue, boolean overdue) {
    }

    public record DocDetail(DocRow doc, List<RevisionView> revisions, AckStatus ack, List<CopyView> copies) {
    }

    public record DocComplianceItem(Long docId, String docCode, String title, String categoryLabel,
                                    String ownerDept, Integer currentRev, String issueType,
                                    String issueTypeLabel, LocalDate dueDate, Long daysLeft,
                                    String detail, String riskLevel, String riskLabel,
                                    String recommendedAction) {
    }

    public record DocComplianceBoard(int totalCount, int overdueCount, int reviewDueCount,
                                     int ackPendingCount, int copyRetrieveCount, int pendingApproveCount,
                                     List<DocComplianceItem> rows) {
    }

    public record IssueReq(@NotBlank(message = "请填写持有人或存放地点") @Size(max = 100) String holder,
                           @jakarta.validation.constraints.Min(value = 1, message = "份数至少 1") @jakarta.validation.constraints.Max(value = MAX_COPIES_PER_ISSUE, message = "一次最多发 20 份") int quantity) {
    }

    public record CloseReq(@Size(max = 300) String note) {
    }

    @Transactional(readOnly = true)
    public List<DocRow> list() {
        return rows(docs.findTop300ByOrderByDocCodeAsc());
    }

    @Transactional(readOnly = true)
    public DocDetail detail(Long id) {
        return detailOf(docs.findById(id).orElseThrow(() -> BizException.notFound("受控文件")));
    }

    @Transactional(readOnly = true)
    public List<DocRow> pendingApproval() {
        List<Long> ids = revisions.findByStatusOrderById(RevisionStatus.PENDING).stream().map(DocRevision::getDocId).distinct().toList();
        return rows(docs.findAllById(ids));
    }

    /** 复审快到期或已超期的在用文件。文件数量有限（几百份），按在用文件算。 */
    @Transactional(readOnly = true)
    public List<DocRow> reviewDue() {
        LocalDate until = time.today().plusDays(REVIEW_WARN_DAYS);
        return rows(docs.findByStatus(DocStatus.ACTIVE)).stream().filter(d -> d.reviewDue() != null && !d.reviewDue().isAfter(until)).toList();
    }

    /** 受控文件合规闭环监控看板（V69）：汇聚复审到期/超期、培训签收超期/未完成、旧版纸质副本待收回与待签批事项。 */
    @Transactional(readOnly = true)
    public DocComplianceBoard complianceBoard() {
        LocalDate today = time.today();
        LocalDate reviewWarnUntil = today.plusDays(REVIEW_WARN_DAYS);
        List<DocRow> all = rows(docs.findAll());
        List<DocComplianceItem> items = new java.util.ArrayList<>();
        int overdueCnt = 0;
        int reviewDueCnt = 0;
        int ackPendingCnt = 0;
        int copyRetrieveCnt = 0;
        int pendingApproveCnt = 0;

        for (DocRow d : all) {
            if (d.copiesToRetrieve() > 0) {
                Long daysLeft = d.retrieveDue() == null ? null : java.time.temporal.ChronoUnit.DAYS.between(today, d.retrieveDue());
                boolean overdue = d.retrieveOverdue() || (daysLeft != null && daysLeft < 0);
                if (overdue) {
                    overdueCnt++;
                }
                copyRetrieveCnt++;
                items.add(new DocComplianceItem(
                        d.id(), d.docCode(), d.title(), d.categoryLabel(), d.ownerDept(), d.currentRev(),
                        "COPY_RETRIEVE", "纸质副本回收", d.retrieveDue(), daysLeft,
                        d.copiesToRetrieve() + " 份旧版/作废纸质副本未收回（在用共 " + d.copiesOut() + " 份）",
                        overdue ? "OVERDUE" : "DUE_SOON", overdue ? "回收已超期" : "纸质待回收",
                        overdue ? "旧版纸质副本已超期未收回，请立即到使用现场收回销毁防止误用"
                                : "请于 " + d.retrieveDue() + " 前收回全部旧版纸质副本"));
            }
            if (d.status() == DocStatus.ACTIVE && d.reviewDue() != null && !d.reviewDue().isAfter(reviewWarnUntil)) {
                long daysLeft = java.time.temporal.ChronoUnit.DAYS.between(today, d.reviewDue());
                boolean overdue = d.reviewOverdue() || daysLeft < 0;
                if (overdue) {
                    overdueCnt++;
                }
                reviewDueCnt++;
                items.add(new DocComplianceItem(
                        d.id(), d.docCode(), d.title(), d.categoryLabel(), d.ownerDept(), d.currentRev(),
                        "REVIEW_DUE", "定期复审", d.reviewDue(), daysLeft,
                        "复审周期 " + d.reviewMonths() + " 个月，到期日 " + d.reviewDue(),
                        overdue ? "OVERDUE" : "DUE_SOON", overdue ? "复审已超期" : "复审将到期",
                        overdue ? "定期复审已超期，请质量安全负责人尽快复审确认或启动修订"
                                : "距复审到期仅 " + daysLeft + " 天，请提前安排文件复审"));
            }
            if (d.status() == DocStatus.ACTIVE && d.ackPending() > 0 && d.currentRev() != null) {
                LocalDate ackDue = d.currentEffective() == null ? null : d.currentEffective().plusDays(ACK_DAYS);
                Long daysLeft = ackDue == null ? null : java.time.temporal.ChronoUnit.DAYS.between(today, ackDue);
                boolean overdue = d.ackOverdue() || (daysLeft != null && daysLeft < 0);
                if (overdue) {
                    overdueCnt++;
                }
                ackPendingCnt++;
                items.add(new DocComplianceItem(
                        d.id(), d.docCode(), d.title(), d.categoryLabel(), d.ownerDept(), d.currentRev(),
                        "ACK_PENDING", "岗位培训签收", ackDue, daysLeft,
                        "第 " + d.currentRev() + " 版还有 " + d.ackPending() + " 人未确认已读",
                        overdue ? "OVERDUE" : "PENDING", overdue ? "签收已超期" : "待岗位签收",
                        overdue ? "新版文件生效超 " + ACK_DAYS + " 天仍有岗位未签收，请立即催办培训确认"
                                : "请提醒相关岗位人员于 " + ackDue + " 前完成已读确认"));
            }
            if (d.status() == DocStatus.ACTIVE && d.pendingRevisionId() != null) {
                pendingApproveCnt++;
                items.add(new DocComplianceItem(
                        d.id(), d.docCode(), d.title(), d.categoryLabel(), d.ownerDept(), d.currentRev(),
                        "PENDING_APPROVAL", "版本待签批", null, null,
                        "存在修订草稿等待质量安全负责人签批生效",
                        "PENDING", "待 QSO 签批",
                        "请核对已上传文件附件并由质量安全负责人完成电子签批"));
            }
        }

        items.sort(Comparator.comparingInt((DocComplianceItem x) -> complianceRiskRank(x.riskLevel()))
                .thenComparing(DocComplianceItem::daysLeft, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(DocComplianceItem::docCode));

        return new DocComplianceBoard(items.size(), overdueCnt, reviewDueCnt, ackPendingCnt,
                copyRetrieveCnt, pendingApproveCnt, items);
    }

    private static int complianceRiskRank(String level) {
        return switch (level) {
            case "OVERDUE" -> 0;
            case "DUE_SOON" -> 1;
            default -> 2;
        };
    }

    @Transactional
    public DocDetail create(CreateReq req) {
        String code = req.docCode().trim().toUpperCase(Locale.ROOT);
        if (docs.existsByDocCode(code)) {
            throw new BizException("文件编号 " + code + " 已存在，要修改请在原文件上出新版");
        }
        ControlledDoc d = new ControlledDoc();
        d.setDocCode(code);
        d.setTitle(req.title().trim());
        d.setCategory(req.category());
        d.setOwnerDept(blankToNull(req.ownerDept()));
        d.setReviewMonths(req.reviewMonths() == null ? 24 : req.reviewMonths());
        d.setTrainRoles(joinRoles(req.trainRoles()));
        d.setStatus(DocStatus.ACTIVE);
        d.setCreatedBy(currentUser.usernameOrSystem());
        d.setCreatedAt(time.now());
        docs.save(d);
        newRevision(d, req.changeSummary(), req.effectiveDate());
        audit.log("新建受控文件", d.getDocCode(), d.getTitle() + "，" + d.getCategory().getLabel() + "，复审周期 " + d.getReviewMonths() + " 个月");
        return detailOf(d);
    }

    @Transactional
    public DocDetail revise(Long docId, ReviseReq req) {
        ControlledDoc d = lockActive(docId, "修订");
        if (revisions.findByDocIdOrderByRevNoDesc(docId).stream().anyMatch(r -> r.getStatus() == RevisionStatus.PENDING)) {
            throw new BizException(d.getDocCode() + " 已有一版待签批，签批或退回后再修订");
        }
        DocRevision r = newRevision(d, req.changeSummary(), req.effectiveDate());
        audit.log("修订受控文件", d.getDocCode(), "第 " + r.getRevNo() + " 版：" + r.getChangeSummary());
        return detailOf(d);
    }

    /** 签批：先锁文件（路径里带文件 id），再看版本。没有附件不能签。 */
    @Transactional
    public DocDetail approve(Long docId, Long revId, ApproveReq req) {
        ControlledDoc d = lockActive(docId, "签批");
        DocRevision r = pendingRevision(d, revId);
        AppUser signer = signature.verify(req.password());
        if (signer.getUsername().equals(r.getDraftedBy())) {
            throw new BizException("签批人不能是起草人");
        }
        if (!attachments.existsByOwnerTypeAndOwnerId(OwnerType.DOC_REVISION, r.getId())) {
            throw new BizException("第 " + r.getRevNo() + " 版还没有上传文件，不能签批");
        }
        LocalDate today = time.today();
        if (r.getEffectiveDate() == null || r.getEffectiveDate().isBefore(today)) {
            r.setEffectiveDate(today);
        }
        r.setStatus(RevisionStatus.APPROVED);
        r.setApprovedBy(signer.getUsername());
        r.setApprovedAt(time.now());
        revisions.save(r);
        // 出新版也算复审过，从生效日期起算下一次
        d.setLastReviewedOn(r.getEffectiveDate());
        docs.save(d);
        audit.log("签批受控文件", d.getDocCode(), "第 " + r.getRevNo() + " 版，" + r.getEffectiveDate() + " 生效");
        return detailOf(d);
    }

    @Transactional
    public DocDetail reject(Long docId, Long revId, NoteReq req) {
        ControlledDoc d = lockActive(docId, "退回");
        DocRevision r = pendingRevision(d, revId);
        r.setStatus(RevisionStatus.REJECTED);
        r.setRejectNote(req.note().trim());
        revisions.save(r);
        audit.log("退回受控文件", d.getDocCode(), "第 " + r.getRevNo() + " 版", req.note().trim());
        return detailOf(d);
    }

    /** 定期复审：确认现行版本仍然适用，不出新版。 */
    @Transactional
    public DocDetail review(Long docId, NoteReq req) {
        ControlledDoc d = lockActive(docId, "复审");
        if (current(revisions.findByDocIdOrderByRevNoDesc(docId)).isEmpty()) {
            throw new BizException(d.getDocCode() + " 还没有生效的版本，不用复审");
        }
        d.setLastReviewedOn(time.today());
        docs.save(d);
        audit.log("复审受控文件", d.getDocCode(), "确认现行版本仍然适用", req.note().trim());
        return detailOf(d);
    }

    /** 作废文件：待签批的版本一起退回。记录保留。 */
    @Transactional
    public DocDetail obsolete(Long docId, NoteReq req) {
        ControlledDoc d = lockActive(docId, "作废");
        for (DocRevision r : revisions.findByDocIdOrderByRevNoDesc(docId)) {
            if (r.getStatus() == RevisionStatus.PENDING) {
                r.setStatus(RevisionStatus.REJECTED);
                r.setRejectNote("文件已作废");
                revisions.save(r);
            }
        }
        d.setStatus(DocStatus.OBSOLETE);
        d.setObsoleteReason(req.note().trim());
        d.setObsoletedOn(time.today());
        docs.save(d);
        audit.log("作废受控文件", d.getDocCode(), d.getTitle(), req.note().trim());
        return detailOf(d);
    }

    /** 改需要确认的岗位。已确认的记录不受影响，按新岗位重新算谁还没确认。 */
    @Transactional
    public DocDetail setTrainRoles(Long docId, RolesReq req) {
        ControlledDoc d = lockActive(docId, "改确认岗位");
        String old = d.getTrainRoles();
        d.setTrainRoles(joinRoles(req.trainRoles()));
        docs.save(d);
        audit.log("修改文件确认岗位", d.getDocCode(), roleLabels(old) + " 改为 " + roleLabels(d.getTrainRoles()));
        return detailOf(d);
    }

    /** 发放纸质副本：只发现行版本，按版本连续编号。先锁文件，同时发放不会重号。 */
    @Transactional
    public DocDetail issueCopies(Long docId, IssueReq req) {
        ControlledDoc d = lockActive(docId, "发放纸质副本");
        DocRevision cur = current(revisions.findByDocIdOrderByRevNoDesc(docId))
                .orElseThrow(() -> new BizException(d.getDocCode() + " 还没有生效的版本，不能发放纸质副本"));
        int next = copies.maxCopyNo(cur.getId()) + 1;
        String holder = req.holder().trim();
        for (int i = 0; i < req.quantity(); i++) {
            DocCopy c = new DocCopy();
            c.setDocId(d.getId());
            c.setRevisionId(cur.getId());
            c.setCopyNo(next + i);
            c.setHolder(holder);
            c.setStatus(DocCopyStatus.ISSUED);
            c.setIssuedBy(currentUser.usernameOrSystem());
            c.setIssuedAt(time.now());
            copies.save(c);
        }
        audit.log("发放受控文件纸质副本", d.getDocCode(), "第 " + cur.getRevNo() + " 版第 " + copyRange(next, req.quantity()) + " 份，持有人 " + holder);
        return detailOf(d);
    }

    /** 收回纸质副本。文件作废后也要能收回，所以不要求文件在用。 */
    @Transactional
    public DocDetail retrieveCopy(Long docId, Long copyId, CloseReq req) {
        return closeCopy(docId, copyId, DocCopyStatus.RETRIEVED, blankToNull(req.note()));
    }

    /** 登记遗失：要写原因。 */
    @Transactional
    public DocDetail loseCopy(Long docId, Long copyId, NoteReq req) {
        return closeCopy(docId, copyId, DocCopyStatus.LOST, req.note().trim());
    }

    private DocDetail closeCopy(Long docId, Long copyId, DocCopyStatus to, String note) {
        // 先锁文件（事务第一句），和发放、作废串行；副本 id 来自路径，属于这份文件才处理
        ControlledDoc d = docs.lockById(docId).orElseThrow(() -> BizException.notFound("受控文件"));
        DocCopy c = copies.findById(copyId).filter(x -> x.getDocId().equals(d.getId())).orElseThrow(() -> BizException.notFound("纸质副本"));
        DocRevision r = revisions.findById(c.getRevisionId()).orElseThrow();
        if (c.getStatus() != DocCopyStatus.ISSUED) {
            throw new BizException("第 " + r.getRevNo() + " 版第 " + c.getCopyNo() + " 份当前为" + c.getStatus().getLabel());
        }
        c.setStatus(to);
        c.setClosedBy(currentUser.usernameOrSystem());
        c.setClosedAt(time.now());
        c.setCloseNote(note);
        copies.save(c);
        audit.log(to == DocCopyStatus.RETRIEVED ? "收回受控文件纸质副本" : "受控文件纸质副本遗失", d.getDocCode(),
                "第 " + r.getRevNo() + " 版第 " + c.getCopyNo() + " 份，持有人 " + c.getHolder(), note);
        return detailOf(d);
    }

    private static String copyRange(int from, int n) {
        return n == 1 ? String.valueOf(from) : from + "～" + (from + n - 1);
    }

    /** 要收回的在用副本：版本已不是现行（以新版生效日期起算），或文件已作废（以作废日期起算）。返回期限，不用收回的返回 null。 */
    private LocalDate retrieveDue(ControlledDoc d, DocCopy c, Optional<DocRevision> cur) {
        if (c.getStatus() != DocCopyStatus.ISSUED) {
            return null;
        }
        if (d.getStatus() == DocStatus.OBSOLETE) {
            return (d.getObsoletedOn() == null ? time.today() : d.getObsoletedOn()).plusDays(RETRIEVE_DAYS);
        }
        if (cur.isPresent() && !cur.get().getId().equals(c.getRevisionId())) {
            return cur.get().getEffectiveDate().plusDays(RETRIEVE_DAYS);
        }
        return null;
    }

    /** 有纸质副本要收回的文件，在用和已作废的都算。 */
    @Transactional(readOnly = true)
    public List<DocRow> copiesToRetrieve() {
        return rows(docs.findAllById(copies.findDocIdsByStatus(DocCopyStatus.ISSUED))).stream().filter(d -> d.copiesToRetrieve() > 0).toList();
    }

    /** 本人确认已阅读。只有应确认的人能确认，每人每版一次。先锁文件，避免重复确认撞唯一约束。 */
    @Transactional
    public DocDetail ack(Long docId, Long revId) {
        ControlledDoc d = lockActive(docId, "确认");
        DocRevision r = revisions.findById(revId).filter(x -> x.getDocId().equals(d.getId())).orElseThrow(() -> BizException.notFound("修订版本"));
        List<DocRevision> revs = revisions.findByDocIdOrderByRevNoDesc(d.getId());
        DocRevision target = ackTarget(revs).orElse(null);
        if (target == null || !target.getId().equals(r.getId())) {
            throw new BizException("第 " + r.getRevNo() + " 版不是现行或待生效的版本，不用确认");
        }
        String me = currentUser.usernameOrSystem();
        if (requiredUsers(d).stream().noneMatch(u -> u.getUsername().equals(me))) {
            throw new BizException("您的岗位不需要确认这份文件");
        }
        if (acks.existsByRevisionIdAndUsername(r.getId(), me)) {
            throw new BizException("您已经确认过第 " + r.getRevNo() + " 版");
        }
        DocAck a = new DocAck();
        a.setRevisionId(r.getId());
        a.setUsername(me);
        a.setAckedAt(time.now());
        acks.save(a);
        audit.log("确认已读受控文件", d.getDocCode(), "第 " + r.getRevNo() + " 版");
        return detailOf(d);
    }

    /** 当前账号还没确认的文件。 */
    @Transactional(readOnly = true)
    public List<MyAck> myPending() {
        var me = currentUser.find().orElse(null);
        if (me == null) {
            return List.of();
        }
        List<ControlledDoc> mine = docs.findByStatus(DocStatus.ACTIVE).stream()
                .filter(d -> parseRoles(d.getTrainRoles()).stream().anyMatch(me::hasRole)).toList();
        if (mine.isEmpty()) {
            return List.of();
        }
        Map<Long, List<DocRevision>> byDoc = revisions.findByDocIdIn(mine.stream().map(ControlledDoc::getId).toList()).stream().collect(Collectors.groupingBy(DocRevision::getDocId));
        List<MyAck> out = new java.util.ArrayList<>();
        for (ControlledDoc d : mine) {
            ackTarget(byDoc.getOrDefault(d.getId(), List.of())).filter(r -> !acks.existsByRevisionIdAndUsername(r.getId(), me.getUsername()))
                    .ifPresent(r -> out.add(new MyAck(d.getId(), d.getDocCode(), d.getTitle(), r.getId(), r.getRevNo(), r.getEffectiveDate())));
        }
        return out;
    }

    /** 现行版本已生效、还有人没确认的在用文件。 */
    @Transactional(readOnly = true)
    public List<DocRow> ackPending() {
        return rows(docs.findByStatus(DocStatus.ACTIVE)).stream().filter(d -> d.ackPending() > 0 && d.currentRev() != null).toList();
    }

    /** 要确认的版本：现行版本；没有现行的（首版还没到生效日期）就是待生效的那版。 */
    private Optional<DocRevision> ackTarget(List<DocRevision> revs) {
        Optional<DocRevision> cur = current(revs);
        return cur.isPresent() ? cur : upcoming(revs);
    }

    private List<com.cosmetic.scm.security.AppUser> requiredUsers(ControlledDoc d) {
        List<Role> roles = parseRoles(d.getTrainRoles());
        if (roles.isEmpty()) {
            return List.of();
        }
        return users.findAll().stream().filter(u -> u.isEnabled() && !u.isPortal() && roles.stream().anyMatch(u::hasRole))
                .sorted(Comparator.comparing(com.cosmetic.scm.security.AppUser::getUsername)).toList();
    }

    private static String joinRoles(java.util.Set<Role> roles) {
        if (roles == null || roles.isEmpty()) {
            return null;
        }
        if (roles.stream().anyMatch(Role::isPortal)) {
            throw new BizException("门户账号不需要确认内部文件");
        }
        return roles.stream().map(Role::name).sorted().collect(Collectors.joining(","));
    }

    private static List<Role> parseRoles(String s) {
        if (s == null || s.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(s.split(",")).map(Role::valueOf).toList();
    }

    private static String roleLabels(String s) {
        List<Role> r = parseRoles(s);
        return r.isEmpty() ? "不需要确认" : r.stream().map(Role::getLabel).collect(Collectors.joining("、"));
    }

    private DocRevision newRevision(ControlledDoc d, String summary, LocalDate effectiveDate) {
        if (effectiveDate != null && effectiveDate.isBefore(time.today())) {
            throw new BizException("生效日期不能早于今天");
        }
        DocRevision r = new DocRevision();
        r.setDocId(d.getId());
        r.setRevNo(revisions.maxRev(d.getId()) + 1);
        r.setChangeSummary(summary.trim());
        r.setEffectiveDate(effectiveDate);
        r.setStatus(RevisionStatus.PENDING);
        r.setDraftedBy(currentUser.usernameOrSystem());
        r.setDraftedAt(time.now());
        return revisions.save(r);
    }

    private ControlledDoc lockActive(Long id, String action) {
        ControlledDoc d = docs.lockById(id).orElseThrow(() -> BizException.notFound("受控文件"));
        if (d.getStatus() != DocStatus.ACTIVE) {
            throw new BizException(d.getDocCode() + " 已作废，不能" + action);
        }
        return d;
    }

    private DocRevision pendingRevision(ControlledDoc d, Long revId) {
        DocRevision r = revisions.findById(revId).filter(x -> x.getDocId().equals(d.getId())).orElseThrow(() -> BizException.notFound("修订版本"));
        if (r.getStatus() != RevisionStatus.PENDING) {
            throw new BizException("第 " + r.getRevNo() + " 版当前为" + r.getStatus().getLabel());
        }
        return r;
    }

    private Optional<DocRevision> current(List<DocRevision> revs) {
        LocalDate today = time.today();
        return revs.stream().filter(r -> r.getStatus() == RevisionStatus.APPROVED && !r.getEffectiveDate().isAfter(today)).max(Comparator.comparing(DocRevision::getRevNo));
    }

    private Optional<DocRevision> upcoming(List<DocRevision> revs) {
        LocalDate today = time.today();
        return revs.stream().filter(r -> r.getStatus() == RevisionStatus.APPROVED && r.getEffectiveDate().isAfter(today)).max(Comparator.comparing(DocRevision::getRevNo));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private List<DocRow> rows(List<ControlledDoc> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, List<DocRevision>> byDoc = revisions.findByDocIdIn(list.stream().map(ControlledDoc::getId).toList()).stream().collect(Collectors.groupingBy(DocRevision::getDocId));
        LocalDate today = time.today();
        List<com.cosmetic.scm.security.AppUser> internal = list.stream().anyMatch(d -> d.getTrainRoles() != null)
                ? users.findAll().stream().filter(u -> u.isEnabled() && !u.isPortal()).toList() : List.of();
        List<Long> targetIds = list.stream().map(d -> ackTarget(byDoc.getOrDefault(d.getId(), List.of())).map(DocRevision::getId).orElse(null)).filter(java.util.Objects::nonNull).toList();
        Map<Long, java.util.Set<String>> ackedBy = acks.findByRevisionIdIn(targetIds).stream()
                .collect(Collectors.groupingBy(DocAck::getRevisionId, Collectors.mapping(DocAck::getUsername, Collectors.toSet())));
        Map<Long, List<DocCopy>> outBy = copies.findByDocIdInAndStatus(list.stream().map(ControlledDoc::getId).toList(), DocCopyStatus.ISSUED).stream()
                .collect(Collectors.groupingBy(DocCopy::getDocId));
        return list.stream().map(d -> {
            List<DocRevision> revs = byDoc.getOrDefault(d.getId(), List.of());
            Optional<DocRevision> cur = current(revs);
            Optional<DocRevision> up = upcoming(revs);
            Long pending = revs.stream().filter(r -> r.getStatus() == RevisionStatus.PENDING).map(DocRevision::getId).findFirst().orElse(null);
            LocalDate due = d.getStatus() == DocStatus.ACTIVE && cur.isPresent() && d.getLastReviewedOn() != null ? d.getLastReviewedOn().plusMonths(d.getReviewMonths()) : null;
            List<Role> roles = parseRoles(d.getTrainRoles());
            Optional<DocRevision> target = ackTarget(revs);
            int ackPending = 0;
            if (d.getStatus() == DocStatus.ACTIVE && target.isPresent() && !roles.isEmpty()) {
                java.util.Set<String> done = ackedBy.getOrDefault(target.get().getId(), java.util.Set.of());
                ackPending = (int) internal.stream().filter(u -> roles.stream().anyMatch(u::hasRole) && !done.contains(u.getUsername())).count();
            }
            boolean ackOverdue = ackPending > 0 && cur.isPresent() && cur.get().getEffectiveDate().plusDays(ACK_DAYS).isBefore(today);
            List<DocCopy> out = outBy.getOrDefault(d.getId(), List.of());
            List<LocalDate> dues = out.stream().map(c -> retrieveDue(d, c, cur)).filter(java.util.Objects::nonNull).sorted().toList();
            LocalDate retrieveDue = dues.isEmpty() ? null : dues.get(0);
            return new DocRow(d.getId(), d.getDocCode(), d.getTitle(), d.getCategory(), d.getCategory().getLabel(), d.getOwnerDept(), d.getReviewMonths(), d.getStatus(), d.getStatus().getLabel(),
                    cur.map(DocRevision::getRevNo).orElse(null), cur.map(DocRevision::getEffectiveDate).orElse(null), pending, up.map(DocRevision::getRevNo).orElse(null),
                    up.map(DocRevision::getEffectiveDate).orElse(null), d.getLastReviewedOn(), due, due != null && due.isBefore(today), d.getObsoleteReason(), roles, ackPending, ackOverdue,
                    out.size(), dues.size(), retrieveDue, retrieveDue != null && retrieveDue.isBefore(today));
        }).toList();
    }

    private List<CopyView> copyViews(ControlledDoc d, List<DocRevision> revs) {
        Map<Long, Integer> revNos = revs.stream().collect(Collectors.toMap(DocRevision::getId, DocRevision::getRevNo));
        Optional<DocRevision> cur = current(revs);
        LocalDate today = time.today();
        return copies.findByDocIdOrderByIdDesc(d.getId()).stream().map(c -> {
            LocalDate due = retrieveDue(d, c, cur);
            return new CopyView(c.getId(), c.getRevisionId(), revNos.get(c.getRevisionId()), c.getCopyNo(), c.getHolder(), c.getStatus(), c.getStatus().getLabel(), c.getIssuedBy(),
                    c.getIssuedAt(), c.getClosedBy(), c.getClosedAt(), c.getCloseNote(), due != null, due, due != null && due.isBefore(today));
        }).toList();
    }

    private DocDetail detailOf(ControlledDoc d) {
        List<DocRevision> revs = revisions.findByDocIdOrderByRevNoDesc(d.getId());
        Long cur = current(revs).map(DocRevision::getId).orElse(null);
        Long up = upcoming(revs).map(DocRevision::getId).orElse(null);
        AckStatus ack = null;
        Optional<DocRevision> target = ackTarget(revs);
        List<com.cosmetic.scm.security.AppUser> people = d.getStatus() == DocStatus.ACTIVE ? requiredUsers(d) : List.of();
        if (target.isPresent() && !people.isEmpty()) {
            Map<String, LocalDateTime> done = acks.findByRevisionIdIn(List.of(target.get().getId())).stream().collect(Collectors.toMap(DocAck::getUsername, DocAck::getAckedAt, (x, y) -> x));
            List<AckView> views = people.stream().map(u -> new AckView(u.getUsername(), u.getDisplayName(), done.get(u.getUsername()))).toList();
            ack = new AckStatus(target.get().getId(), target.get().getRevNo(), views, (int) views.stream().filter(v -> v.ackedAt() != null).count(), views.size());
        }
        return new DocDetail(rows(List.of(d)).get(0), revs.stream().map(r -> new RevisionView(r.getId(), r.getRevNo(), r.getChangeSummary(), r.getEffectiveDate(), r.getStatus(), r.getStatus().getLabel(),
                r.getId().equals(cur), r.getId().equals(up), r.getRejectNote(), r.getDraftedBy(), r.getDraftedAt(), r.getApprovedBy(), r.getApprovedAt())).toList(), ack, copyViews(d, revs));
    }
}
