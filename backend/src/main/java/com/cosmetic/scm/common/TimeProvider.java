package com.cosmetic.scm.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 统一按业务时区取“今天”和“现在”，避免服务器时区影响效期计算。 */
@Component
public class TimeProvider {

    private final ZoneId zone;

    public TimeProvider(@Value("${scm.timezone:Asia/Shanghai}") String timezone) {
        this.zone = ZoneId.of(timezone);
    }

    public LocalDate today() {
        return LocalDate.now(zone);
    }

    public LocalDateTime now() {
        return LocalDateTime.now(zone);
    }
}
