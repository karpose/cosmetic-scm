package com.cosmetic.scm.common;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 重计算接口（看板、补货建议）的短时缓存：几十秒内复用结果，并且同一个 key 同时只算一次。
 * 不用它时，多人同时打开看板会同时全量计算，互相拖慢。ttl 为 0 时不缓存（测试环境）。
 * 只放只读的汇总结果，页面上显示了数据生成时间；要保证实时的数据不要用它。
 */
@Component
public class ShortCache {

    private record Entry(Object value, long expiresAt) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public ShortCache(@Value("${scm.cache.ttl-seconds:30}") long ttlSeconds) {
        this.ttlMillis = ttlSeconds * 1000;
    }

    /** 丢弃缓存：用户刚做了会改变结果的操作，或主动点了刷新。 */
    public void evict(String key) {
        entries.remove(key);
    }

    /** 丢弃某一类缓存。 */
    public void evictPrefix(String prefix) {
        entries.keySet().removeIf(k -> k.startsWith(prefix));
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Supplier<T> compute) {
        if (ttlMillis <= 0) {
            return compute.get();
        }
        Entry e = entries.get(key);
        if (e != null && e.expiresAt() > System.currentTimeMillis()) {
            return (T) e.value();
        }
        synchronized (locks.computeIfAbsent(key, k -> new Object())) {
            e = entries.get(key);
            if (e != null && e.expiresAt() > System.currentTimeMillis()) {
                return (T) e.value();
            }
            T value = compute.get();
            entries.put(key, new Entry(value, System.currentTimeMillis() + ttlMillis));
            return value;
        }
    }
}
