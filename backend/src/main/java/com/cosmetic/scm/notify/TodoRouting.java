package com.cosmetic.scm.notify;

import java.util.Map;
import java.util.Set;

import com.cosmetic.scm.domain.Role;

/** 总览待办由哪些岗位处理。个人邮件只收自己岗位的事项；新增待办类型记得在这里加一行，否则只进全量摘要。 */
final class TodoRouting {

    private static final Set<Role> QUALITY = Set.of(Role.QA, Role.QSO);

    private static final Map<String, Set<Role>> ROUTES = Map.ofEntries(
            Map.entry("QC", QUALITY),
            Map.entry("SHIPMENT", Set.of(Role.WAREHOUSE, Role.PURCHASE)),
            Map.entry("PO_LATE", Set.of(Role.PURCHASE)),
            Map.entry("RFQ_AWARD", Set.of(Role.PURCHASE)),
            Map.entry("SUPPLIER_RETURN", QUALITY),
            Map.entry("REG_VIOLATION", QUALITY),
            Map.entry("COMPLIANCE_STALE", QUALITY),
            Map.entry("COMPLIANCE_MISSING", QUALITY),
            Map.entry("STATEMENT_DISPUTE", Set.of(Role.PURCHASE)),
            Map.entry("STATEMENT_APPROVE", Set.of(Role.FINANCE)),
            Map.entry("STATEMENT_PAY", Set.of(Role.FINANCE)),
            Map.entry("RELEASE", Set.of(Role.QSO)),
            Map.entry("MO_ISSUE", Set.of(Role.PRODUCTION, Role.WAREHOUSE)),
            Map.entry("MO_COMPLETE", Set.of(Role.PRODUCTION, Role.WAREHOUSE)),
            Map.entry("OEM_REPORT", Set.of(Role.PRODUCTION)),
            Map.entry("LICENSE", Set.of(Role.PURCHASE, Role.QA, Role.QSO)),
            Map.entry("NEAR_EXPIRY", Set.of(Role.SALES, Role.WAREHOUSE)),
            Map.entry("EXPIRED_MATERIAL", Set.of(Role.WAREHOUSE, Role.QA, Role.QSO)),
            Map.entry("RECALL", Set.of(Role.QA, Role.QSO, Role.SALES)),
            Map.entry("ADVERSE", QUALITY),
            Map.entry("COMPLAINT", QUALITY),
            Map.entry("RETURN", QUALITY),
            Map.entry("DEALER_ORDER", Set.of(Role.SALES)),
            Map.entry("CHANNEL_FAILED", Set.of(Role.SALES)),
            Map.entry("DEVIATION", QUALITY),
            Map.entry("CAPA", QUALITY),
            Map.entry("SUPPLIER_AUDIT", Set.of(Role.QA, Role.QSO, Role.PURCHASE)),
            Map.entry("RETAIN", QUALITY),
            Map.entry("EQUIPMENT", Set.of(Role.QA, Role.QSO, Role.PRODUCTION)),
            Map.entry("FORMULA", Set.of(Role.QSO)),
            Map.entry("FILING", Set.of(Role.QA, Role.QSO, Role.MASTER_DATA)));

    private TodoRouting() {
    }

    static boolean concerns(String kind, Set<Role> roles) {
        Set<Role> target = ROUTES.get(kind);
        return target != null && roles.stream().anyMatch(target::contains);
    }
}
