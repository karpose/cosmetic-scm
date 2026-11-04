package com.cosmetic.scm.qms;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.AuditResult;
import com.cosmetic.scm.domain.AuditType;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 供应商 / 受托厂审核。结论不合格时自动移出合格名录，停止下单。 */
@Entity
@Table(name = "supplier_audit")
@Getter
@Setter
@NoArgsConstructor
public class SupplierAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String auditNo;

    private Long partnerId;

    private LocalDate auditDate;

    @Enumerated(EnumType.STRING)
    private AuditType auditType;

    private Integer score;

    @Enumerated(EnumType.STRING)
    private AuditResult result;

    private String findings;

    private String auditor;

    private LocalDate nextAuditDate;

    private String signedBy;

    private LocalDateTime signedAt;
}
