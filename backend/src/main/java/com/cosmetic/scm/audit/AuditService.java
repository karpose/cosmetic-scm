package com.cosmetic.scm.audit;

import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;

import com.cosmetic.scm.common.TimeProvider;
import com.cosmetic.scm.security.AppUser;
import com.cosmetic.scm.security.CurrentUser;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 审计日志与业务操作在同一事务里写入：业务失败回滚时日志也不会留下。 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditRepository repository;
    private final CurrentUser currentUser;
    private final TimeProvider time;

    @Transactional
    public void log(String action, String target, String detail) {
        log(action, target, detail, null);
    }

    @Transactional
    public void log(String action, String target, String detail, String reason) {
        AuditLog a = new AuditLog();
        a.setCreatedAt(time.now());
        Optional<AppUser> user = currentUser.find();
        a.setUserId(user.map(AppUser::getId).orElse(null));
        boolean integration = user.isEmpty() && currentUser.isIntegration();
        a.setUsername(user.map(AppUser::getUsername).orElse(integration ? "integration" : "system"));
        a.setDisplayName(user.map(AppUser::getDisplayName).orElse(integration ? "电商对接" : "系统"));
        a.setAction(action);
        a.setTarget(truncate(target, 200));
        a.setDetail(truncate(detail, 1000));
        a.setReason(truncate(reason, 500));
        repository.save(a);
    }

    @Transactional(readOnly = true)
    public List<AuditLog> list(String q, int limit) {
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit, 1000)));
        if (q == null || q.isBlank()) {
            return repository.findAllByOrderByIdDesc(page);
        }
        return repository.search(q.trim(), page);
    }

    /** 按日期范围（含首尾两天）查询，任何一端为空表示不限。limit 由调用方决定，不做 1000 条的截断。 */
    @Transactional(readOnly = true)
    public List<AuditLog> search(java.time.LocalDate from, java.time.LocalDate to, String q, int limit) {
        java.time.LocalDateTime start = from == null ? java.time.LocalDate.of(2000, 1, 1).atStartOfDay() : from.atStartOfDay();
        java.time.LocalDateTime end = to == null ? java.time.LocalDate.of(2999, 1, 1).atStartOfDay() : to.plusDays(1).atStartOfDay();
        return repository.searchRange(start, end, q == null ? "" : q.trim(), PageRequest.of(0, Math.max(1, limit)));
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
