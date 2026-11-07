package com.cosmetic.scm.security;

import java.util.Set;

import org.springframework.security.core.context.SecurityContextHolder;

/** 价格和金额只给采购、财务、管理员。新增返回价格的接口要用它，审计和总览也算。 */
public final class PriceAccess {

    private static final Set<String> ROLES = Set.of("ROLE_ADMIN", "ROLE_PURCHASE", "ROLE_FINANCE");

    private PriceAccess() {
    }

    public static boolean allowed() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> ROLES.contains(a.getAuthority()));
    }
}
