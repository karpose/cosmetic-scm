package com.cosmetic.scm.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.common.BizException;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 初始密码未改或密码已过期时，除登录、改密等 /api/auth/** 外的接口一律拒绝，前端据此弹出强制改密。 */
@Configuration
@RequiredArgsConstructor
public class PasswordGate implements WebMvcConfigurer, HandlerInterceptor {

    private final CurrentUser currentUser;
    private final UserService userService;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/**").excludePathPatterns("/api/auth/**", "/api/integration/**", "/api/public/**", "/api/health");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        currentUser.find().map(userService::passwordChangeRequired).ifPresent(reason -> {
            throw new BizException(HttpStatus.FORBIDDEN, "EXPIRED".equals(reason)
                    ? "密码已超过有效期，请先修改密码" : "请先修改初始密码");
        });
        return true;
    }
}
