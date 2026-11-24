package com.cosmetic.scm.qms;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.cosmetic.scm.attachment.AttachmentRepository;
import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.DocNoService;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.AppointmentStatus;
import com.cosmetic.scm.domain.DelegationStatus;
import com.cosmetic.scm.domain.MajorField;
import com.cosmetic.scm.domain.OwnerType;
import com.cosmetic.scm.domain.QsoDuty;
import com.cosmetic.scm.domain.Role;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.CurrentUser;
import com.cosmetic.scm.security.SignatureService;
import com.cosmetic.scm.security.UserRepository;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 质量安全负责人任职档案和协助履职授权（《化妆品生产质量管理规范》第七条、第五十条；检查要点委托生产版第 4、5 项）。
 * 管理员登记任职（经验不满 5 年不能登记），上传任命文件后输入密码生效，同一时间只有一位现任；现任质量安全负责人可以授权本企业其他人
 * 协助第三至第五项职责，有期限、可撤销，要记录监督情况。有质量安全负责人角色、却既不是现任也没有生效授权的账号，系统提示但不拦签批。
 * 规则见 docs/PLM-默认规则.md 的“质量安全负责人”。
 */
@Service
@RequiredArgsConstructor
public class QsoService {

    /** 规范要求的最少经验年限。 */
    public static final int MIN_YEARS = 5;

    /** 一次授权最长多久。 */
    public static final int MAX_DELEGATION_MONTHS = 12;

    /** 生效中的授权多久没有监督记录要提示。 */
    public static final int REVIEW_MONTHS = 6;

    /** 授权到期前多少天提醒。 */
    public static final int EXPIRY_WARN_DAYS = 30;

    private final QsoAppointmentRepository appointments;
    private final QsoDelegationRepository delegations;
    private final QsoDelegationReviewRepository reviews;
    private final UserRepository users;
    private final AttachmentRepository attachments;
    private final SignatureService signature;
    private final DocNoService docNo;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    public record AppointReq(@NotBlank(message = "请选择对应的账号") String username, @NotBlank(message = "请填写姓名") @Size(max = 60) String personName,
                             @NotNull(message = "请选择所学专业类别") MajorField majorField, @Size(max = 100) String majorDetail, @Size(max = 60) String education,
                             @NotNull(message = "请填写经验年限") @Min(value = 0, message = "经验年限不能为负") @Max(value = 60, message = "经验年限不能超过 60 年") Integer yearsExperience,
                             @Size(max = 500) String experience, @NotNull(message = "请选择任职日期") LocalDate appointedOn) {
    }

    public record PasswordReq(@NotBlank(message = "请输入签批密码") String password) {
    }

    public record NoteReq(@NotBlank(message = "请写明原因") @Size(max = 300) String note) {
    }

    public record DelegateReq(@NotBlank(message = "请选择被授权人") String delegateUsername, @NotEmpty(message = "请至少选一项职责") Set<QsoDuty> duties,
                              @NotBlank(message = "请写明具体事项") @Size(max = 300) String scope, @NotNull(message = "请选择开始日期") LocalDate validFrom,
                              @NotNull(message = "请选择结束日期") LocalDate validTo, @NotBlank(message = "请输入签批密码") String password) {
    }

    public record ReviewReq(@NotBlank(message = "请写明监督情况") @Size(max = 500) String note) {
    }

    public record AppointmentView(Long id, String appointNo, String username, String displayName, String personName, MajorField majorField, String majorLabel,
                                  String majorDetail, String education, int yearsExperience, String experience, LocalDate appointedOn, AppointmentStatus status,
                                  String statusLabel, LocalDate endedOn, String endReason, String createdBy, LocalDateTime createdAt, String activatedBy,
                                  LocalDateTime activatedAt) {
    }

    public record ReviewView(LocalDate reviewDate, String note, String reviewedBy) {
    }

    /** state：IN_EFFECT 生效中、NOT_STARTED 未开始、EXPIRED 已到期、REVOKED 已撤销。reviewDue：生效中超过 6 个月没有监督记录。 */
    public record DelegationView(Long id, String delegationNo, Long appointmentId, String qsoName, String delegateUsername, String delegateName,
                                 List<QsoDuty> duties, String dutyLabels, String scope, LocalDate validFrom, LocalDate validTo, DelegationStatus status,
                                 String state, String stateLabel, boolean expiringSoon, boolean reviewDue, String grantedBy, LocalDateTime grantedAt,
                                 String revokedBy, LocalDateTime revokedAt, String revokeReason, List<ReviewView> reviews) {
    }

    /** 有质量安全负责人角色的账号是现任、有授权，还是都没有。 */
    public record UserCheck(String username, String displayName, String basis, boolean ok) {
    }

    public record Option(String code, String label) {
    }

    /** staff：可以被授权的内部账号（启用、非门户），给授权时选人用。 */
    public record Overview(AppointmentView current, List<AppointmentView> appointments, List<DelegationView> delegations, List<UserCheck> qsoUsers,
                           List<Option> staff, List<Option> majors, List<Option> duties) {
    }

    @Transactional(readOnly = true)
    public Overview overview() {
        LocalDate today = time.today();
        Map<String, AppUser> userMap = userMap();
        List<AppointmentView> list = appointments.findAllByOrderByIdDesc().stream().map(a -> view(a, userMap)).toList();
        AppointmentView current = list.stream().filter(a -> a.status() == AppointmentStatus.ACTIVE).findFirst().orElse(null);
        List<DelegationView> dels = delegationViews(delegations.findAllByOrderByIdDesc(), userMap, today);
        List<Option> staff = userMap.values().stream().filter(u -> u.isEnabled() && !u.isPortal())
                .sorted(Comparator.comparing(AppUser::getUsername)).map(u -> new Option(u.getUsername(), nameOf(u))).toList();
        return new Overview(current, list, dels, userChecks(userMap, current, dels), staff,
                Arrays.stream(MajorField.values()).map(m -> new Option(m.name(), m.getLabel())).toList(),
                Arrays.stream(QsoDuty.values()).map(d -> new Option(d.name(), d.getLabel())).toList());
    }

    /** 现任质量安全负责人（自查核对、总览用）。 */
    @Transactional(readOnly = true)
    public Optional<AppointmentView> current() {
        Map<String, AppUser> userMap = userMap();
        return appointments.findFirstByStatus(AppointmentStatus.ACTIVE).map(a -> view(a, userMap));
    }

    /** 生效中、30 天内到期的授权。 */
    @Transactional(readOnly = true)
    public List<DelegationView> expiringSoon() {
        Map<String, AppUser> userMap = userMap();
        return delegationViews(delegations.findByStatus(DelegationStatus.ACTIVE), userMap, time.today()).stream().filter(DelegationView::expiringSoon).toList();
    }

    /** 有质量安全负责人角色、却既不是现任也没有生效授权的账号。还没登记现任时返回空：上线初期只在上线检查提示。 */
    @Transactional(readOnly = true)
    public List<UserCheck> unauthorized() {
        Map<String, AppUser> userMap = userMap();
        AppointmentView current = appointments.findFirstByStatus(AppointmentStatus.ACTIVE).map(a -> view(a, userMap)).orElse(null);
        if (current == null) {
            return List.of();
        }
        List<DelegationView> dels = delegationViews(delegations.findByStatus(DelegationStatus.ACTIVE), userMap, time.today());
        return userChecks(userMap, current, dels).stream().filter(u -> !u.ok()).toList();
    }

    @Transactional
    public AppointmentView appoint(AppointReq req) {
        AppUser u = users.findByUsername(req.username().trim()).orElseThrow(() -> new BizException("账号 " + req.username().trim() + " 不存在"));
        requireQsoAccount(u);
        if (req.yearsExperience() < MIN_YEARS) {
            throw new BizException("质量安全负责人要有 " + MIN_YEARS + " 年以上化妆品生产或质量管理经验，现在只有 " + req.yearsExperience() + " 年");
        }
        if (req.majorField() == MajorField.OTHER && blankToNull(req.majorDetail()) == null) {
            throw new BizException("专业类别选了“其他相关专业”，请写明专业名称");
        }
        if (req.appointedOn().isAfter(time.today())) {
            throw new BizException("任职日期不能晚于今天");
        }
        QsoAppointment a = new QsoAppointment();
        a.setAppointNo(docNo.next("RZ", time.today()));
        a.setUsername(u.getUsername());
        a.setPersonName(req.personName().trim());
        a.setMajorField(req.majorField());
        a.setMajorDetail(blankToNull(req.majorDetail()));
        a.setEducation(blankToNull(req.education()));
        a.setYearsExperience(req.yearsExperience());
        a.setExperience(blankToNull(req.experience()));
        a.setAppointedOn(req.appointedOn());
        a.setStatus(AppointmentStatus.DRAFT);
        a.setCreatedBy(currentUser.usernameOrSystem());
        a.setCreatedAt(time.now());
        appointments.save(a);
        audit.log("登记质量安全负责人任职", a.getAppointNo(), a.getPersonName() + "（" + a.getUsername() + "），" + a.getMajorField().getLabel()
                + (a.getMajorDetail() == null ? "" : " " + a.getMajorDetail()) + "，经验 " + a.getYearsExperience() + " 年，" + a.getAppointedOn() + " 任职");
        return view(a, userMap());
    }

    /**
     * 生效：要有任命文件等附件，管理员输入密码。前任自动离任，前任给出的授权一并撤销。
     * 先锁这份任职（事务第一句），再锁前任；前任的 id 只查那一列，不先读实体。
     */
    @Transactional
    public AppointmentView activate(Long id, PasswordReq req) {
        QsoAppointment a = appointments.lockById(id).orElseThrow(() -> BizException.notFound("任职档案"));
        if (a.getStatus() != AppointmentStatus.DRAFT) {
            throw new BizException("任职 " + a.getAppointNo() + " 当前为" + a.getStatus().getLabel() + "，不能生效");
        }
        AppUser signer = signature.verify(req.password());
        if (!attachments.existsByOwnerTypeAndOwnerId(OwnerType.QSO_APPOINTMENT, a.getId())) {
            throw new BizException("请先上传任命文件和资质证明");
        }
        requireQsoAccount(users.findByUsername(a.getUsername()).orElseThrow(() -> new BizException("账号 " + a.getUsername() + " 不存在")));
        for (Long oldId : appointments.findIdsByStatus(AppointmentStatus.ACTIVE)) {
            QsoAppointment old = appointments.lockById(oldId).orElseThrow();
            if (old.getStatus() != AppointmentStatus.ACTIVE) {
                throw new BizException("质量安全负责人刚刚有变动，请刷新后重试");
            }
            end(old, "由 " + a.getPersonName() + " 接任");
            appointments.saveAndFlush(old);
            audit.log("质量安全负责人离任", old.getAppointNo(), old.getPersonName() + "（" + old.getUsername() + "）", old.getEndReason());
        }
        a.setStatus(AppointmentStatus.ACTIVE);
        a.setOpenKey(AppointmentStatus.ACTIVE.name());
        a.setActivatedBy(signer.getUsername());
        a.setActivatedAt(time.now());
        try {
            appointments.saveAndFlush(a);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new BizException("质量安全负责人刚刚有变动，请刷新后重试");
        }
        audit.log("质量安全负责人任职生效", a.getAppointNo(), a.getPersonName() + "（" + a.getUsername() + "）");
        return view(a, userMap());
    }

    /** 离任（管理员）：给出的授权一并撤销。 */
    @Transactional
    public AppointmentView endAppointment(Long id, NoteReq req) {
        QsoAppointment a = appointments.lockById(id).orElseThrow(() -> BizException.notFound("任职档案"));
        if (a.getStatus() != AppointmentStatus.ACTIVE) {
            throw new BizException("任职 " + a.getAppointNo() + " 当前为" + a.getStatus().getLabel() + "，不能离任");
        }
        end(a, req.note().trim());
        appointments.save(a);
        audit.log("质量安全负责人离任", a.getAppointNo(), a.getPersonName() + "（" + a.getUsername() + "）", req.note().trim());
        return view(a, userMap());
    }

    private void end(QsoAppointment a, String reason) {
        a.setStatus(AppointmentStatus.ENDED);
        a.setOpenKey(null);
        a.setEndedOn(time.today());
        a.setEndReason(reason);
        for (QsoDelegation d : delegations.lockByAppointmentIdAndStatus(a.getId(), DelegationStatus.ACTIVE)) {
            d.setStatus(DelegationStatus.REVOKED);
            d.setRevokedBy(currentUser.usernameOrSystem());
            d.setRevokedAt(time.now());
            d.setRevokeReason("质量安全负责人更换：" + reason);
            delegations.save(d);
        }
    }

    /** 现任质量安全负责人授权他人协助履职。先锁现任任职，和换人、撤销串行。 */
    @Transactional
    public DelegationView delegate(DelegateReq req) {
        QsoAppointment cur = lockCurrent();
        AppUser signer = signature.verify(req.password());
        requireAppointee(cur, signer.getUsername());
        AppUser u = users.findByUsername(req.delegateUsername().trim()).orElseThrow(() -> new BizException("账号 " + req.delegateUsername().trim() + " 不存在"));
        if (!u.isEnabled() || u.isPortal()) {
            throw new BizException("只能授权本企业在用的内部账号");
        }
        if (u.getUsername().equals(cur.getUsername())) {
            throw new BizException("不能授权给自己");
        }
        LocalDate today = time.today();
        if (req.validFrom().isAfter(req.validTo())) {
            throw new BizException("开始日期不能晚于结束日期");
        }
        if (req.validTo().isBefore(today)) {
            throw new BizException("结束日期不能早于今天");
        }
        if (req.validTo().isAfter(req.validFrom().plusMonths(MAX_DELEGATION_MONTHS))) {
            throw new BizException("一次授权最长 " + MAX_DELEGATION_MONTHS + " 个月，到期后可以重新授权");
        }
        QsoDelegation d = new QsoDelegation();
        d.setDelegationNo(docNo.next("SQ", today));
        d.setAppointmentId(cur.getId());
        d.setDelegateUsername(u.getUsername());
        d.setDuties(req.duties().stream().sorted().map(Enum::name).collect(Collectors.joining(",")));
        d.setScope(req.scope().trim());
        d.setValidFrom(req.validFrom());
        d.setValidTo(req.validTo());
        d.setStatus(DelegationStatus.ACTIVE);
        d.setGrantedBy(signer.getUsername());
        d.setGrantedAt(time.now());
        delegations.save(d);
        audit.log("授权协助履行质量安全负责人职责", d.getDelegationNo(), nameOf(u) + "：" + dutyLabels(parseDuties(d.getDuties())) + "，" + d.getValidFrom() + " 至 "
                + d.getValidTo(), d.getScope());
        return delegationViews(List.of(d), userMap(), today).get(0);
    }

    @Transactional
    public DelegationView revoke(Long id, NoteReq req) {
        QsoAppointment cur = lockCurrent();
        requireAppointee(cur, currentUser.usernameOrSystem());
        QsoDelegation d = lockDelegation(id);
        if (d.getStatus() != DelegationStatus.ACTIVE) {
            throw new BizException("授权 " + d.getDelegationNo() + " 已撤销");
        }
        d.setStatus(DelegationStatus.REVOKED);
        d.setRevokedBy(currentUser.usernameOrSystem());
        d.setRevokedAt(time.now());
        d.setRevokeReason(req.note().trim());
        delegations.save(d);
        audit.log("撤销协助履职授权", d.getDelegationNo(), d.getDelegateUsername(), req.note().trim());
        return delegationViews(List.of(d), userMap(), time.today()).get(0);
    }

    /** 记录对协助履职的监督情况。 */
    @Transactional
    public DelegationView review(Long id, ReviewReq req) {
        QsoAppointment cur = lockCurrent();
        requireAppointee(cur, currentUser.usernameOrSystem());
        QsoDelegation d = lockDelegation(id);
        if (d.getStatus() != DelegationStatus.ACTIVE || !d.getAppointmentId().equals(cur.getId())) {
            throw new BizException("授权 " + d.getDelegationNo() + " 已撤销或不是您给出的，不能记录监督");
        }
        QsoDelegationReview r = new QsoDelegationReview();
        r.setDelegationId(d.getId());
        r.setReviewDate(time.today());
        r.setNote(req.note().trim());
        r.setReviewedBy(currentUser.usernameOrSystem());
        r.setCreatedAt(time.now());
        reviews.save(r);
        audit.log("记录协助履职监督", d.getDelegationNo(), d.getDelegateUsername(), r.getNote());
        return delegationViews(List.of(d), userMap(), time.today()).get(0);
    }

    // ---- 工具 ----

    private QsoAppointment lockCurrent() {
        List<Long> ids = appointments.findIdsByStatus(AppointmentStatus.ACTIVE);
        if (ids.isEmpty()) {
            throw new BizException("还没有生效的质量安全负责人任职，请管理员先登记");
        }
        QsoAppointment cur = appointments.lockById(ids.get(0)).orElseThrow();
        if (cur.getStatus() != AppointmentStatus.ACTIVE) {
            throw new BizException("质量安全负责人刚刚有变动，请刷新后重试");
        }
        return cur;
    }

    private QsoDelegation lockDelegation(Long id) {
        return delegations.lockById(id).orElseThrow(() -> BizException.notFound("授权"));
    }

    private static void requireAppointee(QsoAppointment cur, String username) {
        if (!cur.getUsername().equals(username)) {
            throw new BizException("只有现任质量安全负责人（" + cur.getPersonName() + "）能办理授权");
        }
    }

    private static void requireQsoAccount(AppUser u) {
        if (!u.isEnabled() || u.isPortal()) {
            throw new BizException("账号 " + u.getUsername() + " 已停用或是门户账号");
        }
        if (!u.hasRole(Role.QSO)) {
            throw new BizException("账号 " + u.getUsername() + " 没有质量安全负责人角色，请先在“账号与权限”里加上");
        }
    }

    private Map<String, AppUser> userMap() {
        return users.findAll().stream().collect(Collectors.toMap(AppUser::getUsername, u -> u, (x, y) -> x));
    }

    private static String nameOf(AppUser u) {
        return u.getDisplayName() == null || u.getDisplayName().isBlank() ? u.getUsername() : u.getDisplayName();
    }

    static Set<QsoDuty> parseDuties(String s) {
        if (s == null || s.isBlank()) {
            return EnumSet.noneOf(QsoDuty.class);
        }
        return Arrays.stream(s.split(",")).map(QsoDuty::valueOf).collect(Collectors.toCollection(() -> EnumSet.noneOf(QsoDuty.class)));
    }

    private static String dutyLabels(Set<QsoDuty> duties) {
        return duties.stream().map(QsoDuty::getLabel).collect(Collectors.joining("；"));
    }

    private AppointmentView view(QsoAppointment a, Map<String, AppUser> userMap) {
        AppUser u = userMap.get(a.getUsername());
        return new AppointmentView(a.getId(), a.getAppointNo(), a.getUsername(), u == null ? null : u.getDisplayName(), a.getPersonName(), a.getMajorField(),
                a.getMajorField().getLabel(), a.getMajorDetail(), a.getEducation(), a.getYearsExperience(), a.getExperience(), a.getAppointedOn(), a.getStatus(),
                a.getStatus().getLabel(), a.getEndedOn(), a.getEndReason(), a.getCreatedBy(), a.getCreatedAt(), a.getActivatedBy(), a.getActivatedAt());
    }

    private List<DelegationView> delegationViews(List<QsoDelegation> list, Map<String, AppUser> userMap, LocalDate today) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<Long, String> qsoNames = appointments.findAllById(list.stream().map(QsoDelegation::getAppointmentId).distinct().toList()).stream()
                .collect(Collectors.toMap(QsoAppointment::getId, QsoAppointment::getPersonName));
        Map<Long, List<QsoDelegationReview>> byDel = reviews.findByDelegationIdInOrderByReviewDateAscIdAsc(list.stream().map(QsoDelegation::getId).toList()).stream()
                .collect(Collectors.groupingBy(QsoDelegationReview::getDelegationId));
        List<DelegationView> out = new ArrayList<>();
        for (QsoDelegation d : list) {
            String state;
            if (d.getStatus() == DelegationStatus.REVOKED) {
                state = "REVOKED";
            } else if (today.isBefore(d.getValidFrom())) {
                state = "NOT_STARTED";
            } else if (today.isAfter(d.getValidTo())) {
                state = "EXPIRED";
            } else {
                state = "IN_EFFECT";
            }
            List<QsoDelegationReview> rs = byDel.getOrDefault(d.getId(), List.of());
            LocalDate lastReview = rs.stream().map(QsoDelegationReview::getReviewDate).max(Comparator.naturalOrder()).orElse(null);
            LocalDate since = lastReview != null ? lastReview : d.getValidFrom();
            boolean reviewDue = "IN_EFFECT".equals(state) && since.plusMonths(REVIEW_MONTHS).isBefore(today);
            boolean expiring = "IN_EFFECT".equals(state) && !d.getValidTo().isAfter(today.plusDays(EXPIRY_WARN_DAYS));
            AppUser u = userMap.get(d.getDelegateUsername());
            Set<QsoDuty> duties = parseDuties(d.getDuties());
            out.add(new DelegationView(d.getId(), d.getDelegationNo(), d.getAppointmentId(), qsoNames.get(d.getAppointmentId()), d.getDelegateUsername(),
                    u == null ? d.getDelegateUsername() : nameOf(u), List.copyOf(duties), dutyLabels(duties), d.getScope(), d.getValidFrom(), d.getValidTo(),
                    d.getStatus(), state, switch (state) {
                        case "REVOKED" -> "已撤销";
                        case "NOT_STARTED" -> "未开始";
                        case "EXPIRED" -> "已到期";
                        default -> "生效中";
                    }, expiring, reviewDue, d.getGrantedBy(), d.getGrantedAt(), d.getRevokedBy(), d.getRevokedAt(), d.getRevokeReason(),
                    rs.stream().map(r -> new ReviewView(r.getReviewDate(), r.getNote(), r.getReviewedBy())).toList()));
        }
        return out;
    }

    private List<UserCheck> userChecks(Map<String, AppUser> userMap, AppointmentView current, List<DelegationView> dels) {
        Map<String, DelegationView> inEffect = dels.stream().filter(d -> "IN_EFFECT".equals(d.state()))
                .collect(Collectors.toMap(DelegationView::delegateUsername, d -> d, (x, y) -> x.validTo().isAfter(y.validTo()) ? x : y));
        return userMap.values().stream().filter(u -> u.isEnabled() && !u.isPortal() && u.hasRole(Role.QSO)).sorted(Comparator.comparing(AppUser::getUsername))
                .map(u -> {
                    if (current != null && current.username().equals(u.getUsername())) {
                        return new UserCheck(u.getUsername(), u.getDisplayName(), "现任质量安全负责人", true);
                    }
                    DelegationView d = inEffect.get(u.getUsername());
                    if (d != null) {
                        return new UserCheck(u.getUsername(), u.getDisplayName(), "授权协助：" + d.dutyLabels() + "（至 " + d.validTo() + "）", true);
                    }
                    return new UserCheck(u.getUsername(), u.getDisplayName(), "有质量安全负责人角色，但不是现任，也没有生效的授权", false);
                }).toList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
