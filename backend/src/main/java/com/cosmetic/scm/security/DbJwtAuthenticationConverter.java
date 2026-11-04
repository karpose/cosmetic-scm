package com.cosmetic.scm.security;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * 令牌只证明“是谁”，权限每次从数据库读：账号停用、角色调整立即生效，不用等令牌过期（12 小时）。
 * 改过密码后，之前签发的令牌全部失效；管理员重置密码也一样，可以用来强制下线。
 */
class DbJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final UserRepository users;
    private final ZoneId zone;

    DbJwtAuthenticationConverter(UserRepository users, ZoneId zone) {
        this.users = users;
        this.zone = zone;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        AppUser u = users.findByUsername(jwt.getSubject()).filter(AppUser::isEnabled)
                .orElseThrow(() -> new BadCredentialsException("账号不存在或已停用"));
        if (u.getPasswordChangedAt() != null && jwt.getIssuedAt() != null) {
            // 令牌的签发时间只到秒，比较时也只看到秒，否则改密后马上签发的新令牌会被误判
            Instant changed = u.getPasswordChangedAt().atZone(zone).toInstant().truncatedTo(ChronoUnit.SECONDS);
            if (jwt.getIssuedAt().isBefore(changed)) {
                throw new BadCredentialsException("密码已修改，请重新登录");
            }
        }
        List<SimpleGrantedAuthority> authorities = u.getRoles().stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r.name())).toList();
        return new JwtAuthenticationToken(jwt, authorities, u.getUsername());
    }
}
