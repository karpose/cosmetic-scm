package com.cosmetic.scm.common;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.http.HttpServletRequest;

/** 按客户端 IP 限制每分钟次数。只在内存里，单实例部署够用，重启清零。 */
public final class ClientRateLimiter {

    private final int limitPerMinute;
    private final Map<String, long[]> hits = new ConcurrentHashMap<>();

    public ClientRateLimiter(int limitPerMinute) {
        this.limitPerMinute = limitPerMinute;
    }

    /** 记一次，并返回这一分钟是否已超过上限。 */
    public boolean record(String ip) {
        long minute = Instant.now().getEpochSecond() / 60;
        long[] w = hits.compute(ip, (k, v) -> v == null || v[0] != minute ? new long[] {minute, 1} : new long[] {minute, v[1] + 1});
        if (hits.size() > 10_000) {
            hits.entrySet().removeIf(e -> e.getValue()[0] != minute);
        }
        return w[1] > limitPerMinute;
    }

    /** 这一分钟是否已超过上限，不计数。 */
    public boolean exceeded(String ip) {
        long minute = Instant.now().getEpochSecond() / 60;
        long[] w = hits.get(ip);
        return w != null && w[0] == minute && w[1] >= limitPerMinute;
    }

    public static String clientIp(HttpServletRequest http) {
        // X-Forwarded-For 第一段是客户端自己填的，换个值就能绕过限流；X-Real-IP 由 nginx 覆盖
        String real = http.getHeader("X-Real-IP");
        return real == null || real.isBlank() ? http.getRemoteAddr() : real.trim();
    }
}
