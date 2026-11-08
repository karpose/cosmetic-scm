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

    private final ControlledDocRepository docs;
    private final DocRevisionRepository revisions;
    private final AttachmentRepository attachments;
    private final DocAckRepository acks;
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

    public record DocRow(Long id, String docCode, String title, DocCategory category, String categoryLabel, String ownerDept, int reviewMonths, DocStatus status, String statusLabel,
                         Integer currentRev, LocalDate currentEffective, Long pendingRevisionId, Integer upcomingRev, LocalDate upcomingEffective,
                         LocalDate lastReviewedOn, LocalDate reviewDue, boolean reviewOverdue, String obsoleteReason,
                         List<Role> trainRoles, int ackPending, boolean ackOverdue) {
    }

    public record DocDetail(DocRow doc, List<RevisionView> revisions, AckStatus ack) {
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
            return new DocRow(d.getId(), d.getDocCode(), d.getTitle(), d.getCategory(), d.getCategory().getLabel(), d.getOwnerDept(), d.getReviewMonths(), d.getStatus(), d.getStatus().getLabel(),
                    cur.map(DocRevision::getRevNo).orElse(null), cur.map(DocRevision::getEffectiveDate).orElse(null), pending, up.map(DocRevision::getRevNo).orElse(null),
                    up.map(DocRevision::getEffectiveDate).orElse(null), d.getLastReviewedOn(), due, due != null && due.isBefore(today), d.getObsoleteReason(), roles, ackPending, ackOverdue);
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
                r.getId().equals(cur), r.getId().equals(up), r.getRejectNote(), r.getDraftedBy(), r.getDraftedAt(), r.getApprovedBy(), r.getApprovedAt())).toList(), ack);
    }
}
