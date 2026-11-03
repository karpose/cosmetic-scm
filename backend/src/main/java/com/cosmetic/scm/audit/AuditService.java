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
        a.setUsername(user.map(AppUser::getUsername).orElse("system"));
        a.setDisplayName(user.map(AppUser::getDisplayName).orElse("系统"));
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

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
