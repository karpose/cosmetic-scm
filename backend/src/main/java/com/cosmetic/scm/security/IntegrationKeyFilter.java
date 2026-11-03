package com.cosmetic.scm.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 电商 ERP 调用 /api/integration/** 时用请求头 X-Api-Key 认证，通过后以 ROLE_INTEGRATION 身份继续。
 * 未配置密钥时对接接口整体关闭。只在 SecurityConfig 里手动挂进过滤链，不注册成 Bean，避免被容器重复执行。
 */
public class IntegrationKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Api-Key";
    public static final String PRINCIPAL = "integration";

    private final byte[] apiKey;

    public IntegrationKeyFilter(String apiKey) {
        this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/integration/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (apiKey == null) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "对接接口未启用：服务器没有配置 SCM_INTEGRATION_API_KEY");
            return;
        }
        String given = request.getHeader(HEADER);
        // 定长比较，避免按响应时间猜密钥
        if (given == null || !MessageDigest.isEqual(apiKey, given.getBytes(StandardCharsets.UTF_8))) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "缺少或错误的 " + HEADER);
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(PRINCIPAL, null,
                List.of(new SimpleGrantedAuthority("ROLE_INTEGRATION"))));
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}
