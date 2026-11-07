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

    /** 复审到期前多少天开始提醒。 */
    public static final int REVIEW_WARN_DAYS = 30;

    private final ControlledDocRepository docs;
    private final DocRevisionRepository revisions;
    private final AttachmentRepository attachments;
    private final SignatureService signature;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    public record CreateReq(@NotBlank(message = "请填写文件编号") @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9._-]+", message = "文件编号只能用字母、数字和 . _ -") String docCode,
                            @NotBlank(message = "请填写文件名称") @Size(max = 120) String title, @NotNull(message = "请选择类别") DocCategory category,
                            @Size(max = 60) String ownerDept, @Min(value = 1, message = "复审周期至少 1 个月") @Max(value = 60, message = "复审周期最多 60 个月") Integer reviewMonths,
                            @NotBlank(message = "请写明制定或修订内容") @Size(max = 300) String changeSummary, LocalDate effectiveDate) {
    }

    public record ReviseReq(@NotBlank(message = "请写明修订内容") @Size(max = 300) String changeSummary, LocalDate effectiveDate) {
    }

    public record ApproveReq(@NotBlank(message = "请输入签批密码") String password) {
    }

    public record NoteReq(@NotBlank(message = "请写明原因") @Size(max = 300) String note) {
    }

    public record RevisionView(Long id, int revNo, String changeSummary, LocalDate effectiveDate, RevisionStatus status, String statusLabel, boolean current, boolean upcoming,
                               String rejectNote, String draftedBy, LocalDateTime draftedAt, String approvedBy, LocalDateTime approvedAt) {
    }

    public record DocRow(Long id, String docCode, String title, DocCategory category, String categoryLabel, String ownerDept, int reviewMonths, DocStatus status, String statusLabel,
                         Integer currentRev, LocalDate currentEffective, Long pendingRevisionId, Integer upcomingRev, LocalDate upcomingEffective,
                         LocalDate lastReviewedOn, LocalDate reviewDue, boolean reviewOverdue, String obsoleteReason) {
    }

    public record DocDetail(DocRow doc, List<RevisionView> revisions) {
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
        return list.stream().map(d -> {
            List<DocRevision> revs = byDoc.getOrDefault(d.getId(), List.of());
            Optional<DocRevision> cur = current(revs);
            Optional<DocRevision> up = upcoming(revs);
            Long pending = revs.stream().filter(r -> r.getStatus() == RevisionStatus.PENDING).map(DocRevision::getId).findFirst().orElse(null);
            LocalDate due = d.getStatus() == DocStatus.ACTIVE && cur.isPresent() && d.getLastReviewedOn() != null ? d.getLastReviewedOn().plusMonths(d.getReviewMonths()) : null;
            return new DocRow(d.getId(), d.getDocCode(), d.getTitle(), d.getCategory(), d.getCategory().getLabel(), d.getOwnerDept(), d.getReviewMonths(), d.getStatus(), d.getStatus().getLabel(),
                    cur.map(DocRevision::getRevNo).orElse(null), cur.map(DocRevision::getEffectiveDate).orElse(null), pending, up.map(DocRevision::getRevNo).orElse(null),
                    up.map(DocRevision::getEffectiveDate).orElse(null), d.getLastReviewedOn(), due, due != null && due.isBefore(today), d.getObsoleteReason());
        }).toList();
    }

    private DocDetail detailOf(ControlledDoc d) {
        List<DocRevision> revs = revisions.findByDocIdOrderByRevNoDesc(d.getId());
        Long cur = current(revs).map(DocRevision::getId).orElse(null);
        Long up = upcoming(revs).map(DocRevision::getId).orElse(null);
        return new DocDetail(rows(List.of(d)).get(0), revs.stream().map(r -> new RevisionView(r.getId(), r.getRevNo(), r.getChangeSummary(), r.getEffectiveDate(), r.getStatus(), r.getStatus().getLabel(),
                r.getId().equals(cur), r.getId().equals(up), r.getRejectNote(), r.getDraftedBy(), r.getDraftedAt(), r.getApprovedBy(), r.getApprovedAt())).toList());
    }
}
