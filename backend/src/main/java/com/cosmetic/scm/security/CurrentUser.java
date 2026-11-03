package com.cosmetic.scm.security;

import java.util.Optional;

import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.common.BizException;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** 取当前登录账号。每次都回库校验，停用的账号即使令牌未过期也会被拒绝。 */
@Component
@RequiredArgsConstructor
public class CurrentUser {

    private final UserRepository users;

    public AppUser get() {
        return find().orElseThrow(() -> new BizException(HttpStatus.UNAUTHORIZED, "账号不存在或已停用，请重新登录"));
    }

    public Optional<AppUser> find() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            return Optional.empty();
        }
        return users.findByUsername(auth.getName()).filter(AppUser::isEnabled);
    }

    /** 记录在单据上的操作人；没有登录上下文时（如系统初始化）记为 system。 */
    public String usernameOrSystem() {
        return find().map(AppUser::getUsername).orElse("system");
    }
}
