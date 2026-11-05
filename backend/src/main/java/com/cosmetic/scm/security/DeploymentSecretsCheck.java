package com.cosmetic.scm.security;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 正式环境拒绝用占位值启动。.env.example 里的“请改成至少32位的随机字符串”按字节算够长，能通过密钥长度检查；
 * 忘了改的话，看过仓库的人都能伪造管理员令牌。这类错误不能等到出事才发现。
 */
@Component
@Profile("prod")
public class DeploymentSecretsCheck {

    @Value("${scm.jwt.secret}")
    private String jwtSecret;

    @Value("${spring.datasource.password:}")
    private String dbPassword;

    @Value("${scm.bootstrap-admin.password:}")
    private String adminPassword;

    @PostConstruct
    void check() {
        List<String> bad = problems(jwtSecret, dbPassword, adminPassword);
        if (!bad.isEmpty()) {
            throw new IllegalStateException("这些配置还是默认值或占位符，请在 .env 里改掉后再启动：" + String.join("、", bad));
        }
    }

    static List<String> problems(String jwtSecret, String dbPassword, String adminPassword) {
        List<String> bad = new ArrayList<>();
        if (placeholder(jwtSecret) || jwtSecret.startsWith("dev-only")) {
            bad.add("SCM_JWT_SECRET");
        }
        if (placeholder(dbPassword)) {
            bad.add("SCM_DB_PASSWORD");
        }
        if (placeholder(adminPassword) || "Admin@123456".equals(adminPassword)) {
            bad.add("SCM_ADMIN_PASSWORD");
        }
        return bad;
    }

    private static boolean placeholder(String v) {
        return v != null && v.contains("请改成");
    }
}
