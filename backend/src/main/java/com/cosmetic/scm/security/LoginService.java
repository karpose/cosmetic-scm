package com.cosmetic.scm.security;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.audit.AuditService;
import com.cosmetic.scm.common.BizException;
import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.security.UserService.UserView;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登录与失败锁定：连续输错 scm.security.max-failed-logins 次锁定 lock-minutes 分钟。
 * 登录失败会抛异常，但失败计数必须落库，所以事务对 BizException 不回滚。
 * 账号不存在和密码错误给出同样的提示，不暴露账号是否存在。
 */
@Service
@RequiredArgsConstructor
public class LoginService {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final UserRepository users;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService audit;
    private final TimeProvider time;

    @Value("${scm.security.max-failed-logins:5}")
    private int maxFailedLogins;

    @Value("${scm.security.lock-minutes:15}")
    private int lockMinutes;

    private volatile String decoyHash;

    public record LoginResp(String token, UserView user) {
    }

    @Transactional(noRollbackFor = BizException.class)
    public LoginResp login(String username, String password) {
        AppUser u = users.findByUsername(username.trim()).filter(AppUser::isEnabled).orElse(null);
        if (u == null) {
            // 账号不存在也算一次密码：否则响应快几十倍，能试出哪些账号存在
            passwordEncoder.matches(password, decoyHash());
            throw badCredentials();
        }
        LocalDateTime now = time.now();
        if (u.getLockedUntil() != null && u.getLockedUntil().isAfter(now)) {
            throw new BizException(HttpStatus.LOCKED, "账号已锁定，请于 " + u.getLockedUntil().format(HM) + " 后再试，或联系管理员解锁");
        }
        if (!passwordEncoder.matches(password, u.getPasswordHash())) {
            int failed = u.getFailedLogins() + 1;
            if (failed >= maxFailedLogins) {
                u.setFailedLogins(0);
                u.setLockedUntil(now.plusMinutes(lockMinutes));
                users.save(u);
                audit.logAs(u, "登录锁定", "连续 " + failed + " 次密码错误，锁定至 " + u.getLockedUntil().format(HM));
                throw new BizException(HttpStatus.LOCKED, "密码连续错误 " + failed + " 次，账号已锁定 " + lockMinutes + " 分钟");
            }
            u.setFailedLogins(failed);
            users.save(u);
            audit.logAs(u, "登录失败", "密码错误，连续第 " + failed + " 次");
            throw badCredentials();
        }
        u.setFailedLogins(0);
        u.setLockedUntil(null);
        users.save(u);
        audit.logAs(u, "登录", "");
        return new LoginResp(jwtService.issue(u), userService.view(u));
    }

    private String decoyHash() {
        if (decoyHash == null) {
            decoyHash = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
        }
        return decoyHash;
    }

    private static BizException badCredentials() {
        return new BizException(HttpStatus.UNAUTHORIZED, "账号或密码不正确");
    }
}
