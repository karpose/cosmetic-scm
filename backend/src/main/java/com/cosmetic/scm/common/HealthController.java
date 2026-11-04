package com.cosmetic.scm.common;

import java.util.Map;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 健康检查：给 docker compose、负载均衡和监控用，不需要登录，不暴露任何业务数据。数据库不通时返回 503。 */
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final JdbcTemplate jdbc;
    private final TimeProvider time;

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean db;
        try {
            db = Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class));
        } catch (RuntimeException e) {
            db = false;
        }
        return ResponseEntity.status(db ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", db ? "UP" : "DOWN", "database", db ? "UP" : "DOWN", "time", time.now().toString()));
    }
}
