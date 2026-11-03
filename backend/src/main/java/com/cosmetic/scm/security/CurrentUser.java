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

    /** 记录在单据上的操作人；电商对接调用记为 integration；没有登录上下文时（如系统初始化）记为 system。 */
    public String usernameOrSystem() {
        return find().map(AppUser::getUsername).orElseGet(() -> isIntegration() ? IntegrationKeyFilter.PRINCIPAL : "system");
    }

    public boolean isIntegration() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && IntegrationKeyFilter.PRINCIPAL.equals(auth.getName())
                && auth.getAuthorities().stream().anyMatch(a -> "ROLE_INTEGRATION".equals(a.getAuthority()));
    }

    /** 门户账号所属单位 ID。门户服务的每个查询都按它过滤，保证外部账号只看到本单位的数据。 */
    public Long portalPartnerId() {
        AppUser u = get();
        if (!u.isPortal() || u.getPartnerId() == null) {
            throw new BizException(HttpStatus.FORBIDDEN, "当前账号不是门户账号，或没有绑定所属单位");
        }
        return u.getPartnerId();
    }
}
