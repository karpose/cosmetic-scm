package com.cosmetic.scm.security;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.PartnerKind;
import com.cosmetic.scm.domain.Role;
import com.cosmetic.scm.domain.rules.PasswordPolicy;
import com.cosmetic.scm.master.Partner;
import com.cosmetic.scm.master.PartnerRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号管理。密码按 {@link PasswordPolicy} 校验；管理员新建或重置的密码首次登录须修改，密码超过有效期须修改
 * （scm.security.force-password-change 关闭时不强制，用于本地演示）。
 * 门户账号只能有一个门户角色，并且必须绑定对应类型的往来单位：代工厂门户 → 受托生产企业，经销商门户 → 客户。
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository users;
    private final PartnerRepository partners;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    @Value("${scm.security.force-password-change:true}")
    private boolean forcePasswordChange;

    @Value("${scm.security.password-max-age-days:90}")
    private int passwordMaxAgeDays;

    /** passwordChangeRequired：null 不需要 / INITIAL 初始密码须修改 / EXPIRED 密码已过期 */
    public record UserView(Long id, String username, String displayName, boolean enabled, List<String> roles,
                           Long partnerId, String partnerName, LocalDateTime lockedUntil,
                           String passwordChangeRequired, String email) {
    }

    public record CreateUserReq(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_.\\-]{3,40}$", message = "只能用 3-40 位字母、数字和 _ . -") String username,
            @NotBlank @Size(max = 64) String displayName,
            @NotBlank String password,
            @NotEmpty(message = "至少选择一个角色") Set<Role> roles,
            Long partnerId,
            @jakarta.validation.constraints.Email(message = "邮箱格式不正确") @Size(max = 120) String email) {
    }

    public record UpdateUserReq(@NotBlank @Size(max = 64) String displayName, boolean enabled,
                                @NotEmpty(message = "至少选择一个角色") Set<Role> roles, Long partnerId,
                                @jakarta.validation.constraints.Email(message = "邮箱格式不正确") @Size(max = 120) String email) {
    }

    public record PasswordReq(@NotBlank String password) {
    }

    public record ChangePasswordReq(@NotBlank String oldPassword, @NotBlank String newPassword) {
    }

    @Transactional(readOnly = true)
    public List<UserView> list() {
        Map<Long, Partner> pm = partners.findAll().stream().collect(Collectors.toMap(Partner::getId, Function.identity()));
        return users.findAll().stream()
                .sorted(Comparator.comparing(AppUser::getUsername))
                .map(u -> view(u, pm)).toList();
    }

    @Transactional(readOnly = true)
    public UserView view(AppUser u) {
        Map<Long, Partner> pm = u.getPartnerId() == null ? Map.of()
                : partners.findById(u.getPartnerId()).map(p -> Map.of(p.getId(), p)).orElse(Map.of());
        return view(u, pm);
    }

    private UserView view(AppUser u, Map<Long, Partner> pm) {
        Partner p = u.getPartnerId() == null ? null : pm.get(u.getPartnerId());
        LocalDateTime locked = u.getLockedUntil() != null && u.getLockedUntil().isAfter(time.now()) ? u.getLockedUntil() : null;
        return new UserView(u.getId(), u.getUsername(), u.getDisplayName(), u.isEnabled(),
                u.getRoles().stream().map(Enum::name).sorted().toList(), u.getPartnerId(),
                p == null ? null : p.getName(), locked, passwordChangeRequired(u), u.getEmail());
    }

    /**
     * 截断到整秒。MySQL 的 DATETIME 会把小数秒四舍五入：12:00:00.6 存成 12:00:01，
     * 而紧接着签发的新令牌 iat 是 12:00:00，会被当成改密前的旧令牌拒绝。
     */
    private java.time.LocalDateTime passwordChangeTime() {
        return time.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    }

    /** 当前是否必须先改密码；返回 null 表示不需要。 */
    public String passwordChangeRequired(AppUser u) {
        if (!forcePasswordChange) {
            return null;
        }
        if (u.isMustChangePassword()) {
            return "INITIAL";
        }
        if (passwordMaxAgeDays > 0 && u.getPasswordChangedAt() != null
                && u.getPasswordChangedAt().plusDays(passwordMaxAgeDays).isBefore(time.now())) {
            return "EXPIRED";
        }
        return null;
    }

    @Transactional
    public UserView create(CreateUserReq req) {
        if (users.existsByUsername(req.username())) {
            throw new BizException("账号 " + req.username() + " 已存在");
        }
        requireStrong(req.password(), req.username());
        Long partnerId = checkRoles(req.roles(), req.partnerId());
        AppUser u = newUser(req.username(), req.displayName(), req.password(), req.roles(), partnerId, true);
        u.setEmail(req.email() == null || req.email().isBlank() ? null : req.email().trim());
        audit.log("新增账号", u.getUsername(), "角色 " + u.getRoles() + describePartner(partnerId));
        return view(u);
    }

    /** 不经过审计和复杂度校验的创建，用于首次启动和示例数据。 */
    @Transactional
    public AppUser newUser(String username, String displayName, String rawPassword, Set<Role> roles) {
        return newUser(username, displayName, rawPassword, roles, null, false);
    }

    @Transactional
    public AppUser newUser(String username, String displayName, String rawPassword, Set<Role> roles, Long partnerId,
                           boolean mustChangePassword) {
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setDisplayName(displayName);
        u.setPasswordHash(passwordEncoder.encode(rawPassword));
        u.setEnabled(true);
        u.setCreatedAt(time.now());
        u.setPasswordChangedAt(passwordChangeTime());
        u.setMustChangePassword(mustChangePassword);
        u.setRoles(EnumSet.copyOf(roles));
        u.setPartnerId(partnerId);
        return users.save(u);
    }

    @Transactional
    public UserView update(Long id, UpdateUserReq req) {
        AppUser u = user(id);
        AppUser me = currentUser.get();
        if (u.getId().equals(me.getId())) {
            if (!req.enabled()) {
                throw new BizException("不能停用自己");
            }
            // 否则管理员可以给自己加质量安全负责人，绕过放行的职责分离
            if (!Set.copyOf(u.getRoles()).equals(Set.copyOf(req.roles()))) {
                throw new BizException("不能修改自己的角色，请让另一位管理员操作");
            }
        }
        Long partnerId = checkRoles(req.roles(), req.partnerId());
        Map<String, Object> before = userFields(u);
        u.setDisplayName(req.displayName());
        u.setEmail(req.email() == null || req.email().isBlank() ? null : req.email().trim());
        u.setEnabled(req.enabled());
        u.setRoles(EnumSet.copyOf(req.roles()));
        u.setPartnerId(partnerId);
        String changes = com.cosmetic.scm.common.FieldDiff.of(before, userFields(u));
        audit.log("修改账号", u.getUsername(), changes.isEmpty() ? "内容没有变化" : changes);
        return view(u);
    }

    private Map<String, Object> userFields(AppUser u) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("姓名", u.getDisplayName());
        m.put("邮箱", u.getEmail());
        m.put("启用", u.isEnabled());
        m.put("角色", u.getRoles().stream().map(Role::getLabel).sorted().collect(Collectors.joining("、")));
        m.put("所属单位", u.getPartnerId() == null ? null : partners.findById(u.getPartnerId()).map(Partner::getName).orElse("?"));
        return m;
    }

    @Transactional
    public void resetPassword(Long id, PasswordReq req) {
        AppUser u = user(id);
        requireStrong(req.password(), u.getUsername());
        u.setPasswordHash(passwordEncoder.encode(req.password()));
        u.setPasswordChangedAt(passwordChangeTime());
        u.setMustChangePassword(true);
        u.setFailedLogins(0);
        u.setLockedUntil(null);
        audit.log("重置密码", u.getUsername(), "管理员重置，账号同时解锁；下次登录须修改密码");
    }

    @Transactional
    public UserView unlock(Long id) {
        AppUser u = user(id);
        u.setFailedLogins(0);
        u.setLockedUntil(null);
        audit.log("解锁账号", u.getUsername(), "管理员解锁");
        return view(u);
    }

    @Transactional
    public UserView changeOwnPassword(ChangePasswordReq req) {
        AppUser u = currentUser.get();
        if (!passwordEncoder.matches(req.oldPassword(), u.getPasswordHash())) {
            throw new BizException("原密码不正确");
        }
        if (req.oldPassword().equals(req.newPassword())) {
            throw new BizException("新密码不能和原密码相同");
        }
        requireStrong(req.newPassword(), u.getUsername());
        u.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        u.setPasswordChangedAt(passwordChangeTime());
        u.setMustChangePassword(false);
        users.save(u);
        audit.log("修改本人密码", u.getUsername(), "");
        return view(u);
    }

    private AppUser user(Long id) {
        return users.findById(id).orElseThrow(() -> BizException.notFound("账号"));
    }

    private static void requireStrong(String password, String username) {
        String problem = PasswordPolicy.check(password, username);
        if (problem != null) {
            throw new BizException(problem);
        }
    }

    /** 校验角色组合和所属单位，返回应保存的单位 ID（内部账号为 null）。 */
    private Long checkRoles(Set<Role> roles, Long partnerId) {
        List<Role> portal = roles.stream().filter(Role::isPortal).toList();
        if (portal.isEmpty()) {
            if (partnerId != null) {
                throw new BizException("只有门户账号需要选择所属单位");
            }
            return null;
        }
        if (roles.size() > 1) {
            throw new BizException("门户账号只能有一个门户角色，不能和内部角色同时勾选");
        }
        if (partnerId == null) {
            throw new BizException("门户账号必须选择所属单位");
        }
        Partner p = partners.findById(partnerId).orElseThrow(() -> BizException.notFound("往来单位"));
        PartnerKind need = switch (portal.get(0)) {
            case OEM_PORTAL -> PartnerKind.OEM;
            case SUPPLIER_PORTAL -> PartnerKind.SUPPLIER;
            default -> PartnerKind.CUSTOMER;
        };
        if (p.getKind() != need) {
            throw new BizException(portal.get(0).getLabel() + "账号只能绑定" + need.getLabel() + "，" + p.getName() + " 是"
                    + p.getKind().getLabel());
        }
        return p.getId();
    }

    private String describePartner(Long partnerId) {
        return partnerId == null ? "" : "，所属单位 " + partners.findById(partnerId).map(Partner::getName).orElse("?");
    }
}
