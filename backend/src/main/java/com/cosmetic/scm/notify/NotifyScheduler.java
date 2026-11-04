package com.cosmetic.scm.notify;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 只有 scm.notify.enabled=true 时才启用定时任务；本地开发和测试默认关闭。单实例部署，不需要分布式锁。 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "scm.notify.enabled", havingValue = "true")
@RequiredArgsConstructor
public class NotifyScheduler {

    private final NotifyService notify;

    @Scheduled(cron = "${scm.notify.cron}", zone = "Asia/Shanghai")
    public void daily() {
        notify.sendScheduled();
    }
}
