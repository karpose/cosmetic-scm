package com.cosmetic.scm.security;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.http.HttpServletResponse;

import com.cosmetic.scm.domain.Role;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 无状态 JWT 认证：登录换取令牌，令牌里带角色，接口用 @PreAuthorize 按角色控制。
 * URL 层再划一道边界：门户账号只能访问自己的 /api/portal/**；其余业务接口只对内部角色开放；
 * /api/integration/** 走 X-Api-Key（{@link IntegrationKeyFilter}），不接受用户令牌。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecretKey jwtSecretKey(@Value("${scm.jwt.secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("scm.jwt.secret 至少需要 32 个字节，请设置环境变量 SCM_JWT_SECRET");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
        return NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** 内部岗位角色；门户角色（OEM_PORTAL、DEALER）不在其中。 */
    static final String[] INTERNAL_ROLES = Arrays.stream(Role.values()).filter(r -> !r.isPortal())
            .map(Enum::name).toArray(String[]::new);

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, @Value("${scm.integration.api-key:}") String integrationKey)
            throws Exception {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);

        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/login", "/api/health", "/api/public/**").permitAll()
                        .requestMatchers("/api/auth/**").authenticated()
                        .requestMatchers("/api/integration/**").hasRole("INTEGRATION")
                        .requestMatchers("/api/portal/oem/**").hasRole(Role.OEM_PORTAL.name())
                        .requestMatchers("/api/portal/dealer/**").hasRole(Role.DEALER.name())
                        .requestMatchers("/api/**").hasAnyRole(INTERNAL_ROLES)
                        .anyRequest().permitAll())
                .exceptionHandling(e -> e.accessDeniedHandler((req, resp, ex) -> {
                    resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    resp.setContentType("application/json;charset=UTF-8");
                    resp.getWriter().write("{\"message\":\"你的账号没有访问此功能的权限\"}");
                }))
                .addFilterBefore(new IntegrationKeyFilter(integrationKey), BearerTokenAuthenticationFilter.class)
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));
        return http.build();
    }
}
