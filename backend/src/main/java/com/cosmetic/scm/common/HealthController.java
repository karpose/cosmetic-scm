package com.cosmetic.scm.common;

import java.util.Map;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查：给 docker compose、负载均衡和监控用，不需要登录，不暴露任何业务数据。数据库不通时返回 503。
 * 启动还没完成（端口已经能连上，但 ApplicationRunner 还在跑，比如示例数据还在写）时也返回 503，
 * 冒烟测试按它判断后端就绪，否则第一个用例会读到写了一半的示例数据。
 */
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final JdbcTemplate jdbc;
    private final TimeProvider time;
    private final ApplicationAvailability availability;

    @GetMapping("/api/health")
    public ResponseEntity<Map<String, Object>> health() {
        boolean db;
        try {
            db = Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class));
        } catch (RuntimeException e) {
            db = false;
        }
        boolean ready = availability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC;
        return ResponseEntity.status(db && ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", !db ? "DOWN" : ready ? "UP" : "STARTING", "database", db ? "UP" : "DOWN", "time", time.now().toString()));
    }
}
