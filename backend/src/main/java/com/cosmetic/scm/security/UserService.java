package com.cosmetic.scm.security;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.domain.Role;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final TimeProvider time;
    private final CurrentUser currentUser;

    public record UserView(Long id, String username, String displayName, boolean enabled, List<String> roles) {
        static UserView of(AppUser u) {
            return new UserView(u.getId(), u.getUsername(), u.getDisplayName(), u.isEnabled(),
                    u.getRoles().stream().map(Enum::name).sorted().toList());
        }
    }

    public record CreateUserReq(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_.\\-]{3,40}$", message = "只能用 3-40 位字母、数字和 _ . -") String username,
            @NotBlank @Size(max = 64) String displayName,
            @NotBlank @Size(min = 8, max = 64, message = "密码至少 8 位") String password,
            @NotEmpty(message = "至少选择一个角色") Set<Role> roles) {
    }

    public record UpdateUserReq(@NotBlank @Size(max = 64) String displayName, boolean enabled,
                                @NotEmpty(message = "至少选择一个角色") Set<Role> roles) {
    }

    public record PasswordReq(@NotBlank @Size(min = 8, max = 64, message = "密码至少 8 位") String password) {
    }

    public record ChangePasswordReq(@NotBlank String oldPassword,
                                    @NotBlank @Size(min = 8, max = 64, message = "新密码至少 8 位") String newPassword) {
    }

    @Transactional(readOnly = true)
    public List<UserView> list() {
        return users.findAll().stream()
                .sorted(Comparator.comparing(AppUser::getUsername))
                .map(UserView::of).toList();
    }

    @Transactional
    public UserView create(CreateUserReq req) {
        if (users.existsByUsername(req.username())) {
            throw new BizException("账号 " + req.username() + " 已存在");
        }
        AppUser u = newUser(req.username(), req.displayName(), req.password(), req.roles());
        audit.log("新增账号", u.getUsername(), "角色 " + u.getRoles());
        return UserView.of(u);
    }

    /** 不经过审计的创建，用于首次启动和示例数据。 */
    @Transactional
    public AppUser newUser(String username, String displayName, String rawPassword, Set<Role> roles) {
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setDisplayName(displayName);
        u.setPasswordHash(passwordEncoder.encode(rawPassword));
        u.setEnabled(true);
        u.setCreatedAt(time.now());
        u.setRoles(EnumSet.copyOf(roles));
        return users.save(u);
    }

    @Transactional
    public UserView update(Long id, UpdateUserReq req) {
        AppUser u = users.findById(id).orElseThrow(() -> BizException.notFound("账号"));
        AppUser me = currentUser.get();
        if (u.getId().equals(me.getId()) && (!req.enabled() || !req.roles().contains(Role.ADMIN))) {
            throw new BizException("不能停用自己，或移除自己的管理员角色");
        }
        u.setDisplayName(req.displayName());
        u.setEnabled(req.enabled());
        u.setRoles(EnumSet.copyOf(req.roles()));
        audit.log("修改账号", u.getUsername(), "启用=" + req.enabled() + "，角色 " + u.getRoles());
        return UserView.of(u);
    }

    @Transactional
    public void resetPassword(Long id, PasswordReq req) {
        AppUser u = users.findById(id).orElseThrow(() -> BizException.notFound("账号"));
        u.setPasswordHash(passwordEncoder.encode(req.password()));
        audit.log("重置密码", u.getUsername(), "管理员重置");
    }

    @Transactional
    public void changeOwnPassword(ChangePasswordReq req) {
        AppUser u = currentUser.get();
        if (!passwordEncoder.matches(req.oldPassword(), u.getPasswordHash())) {
            throw new BizException("原密码不正确");
        }
        u.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        users.save(u);
        audit.log("修改本人密码", u.getUsername(), "");
    }
}
