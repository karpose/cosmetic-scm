package com.cosmetic.scm.audit;

import java.util.List;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    @GetMapping
    public List<AuditLog> list(@RequestParam(required = false) String q,
                               @RequestParam(defaultValue = "300") int limit) {
        return auditService.list(q, limit);
    }
}
