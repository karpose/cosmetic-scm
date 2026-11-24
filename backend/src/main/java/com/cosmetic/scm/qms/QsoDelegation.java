package com.cosmetic.scm.qms;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.DelegationStatus;

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

/** 质量安全负责人授权他人协助履职的一份授权。 */
@Entity
@Table(name = "qso_delegation")
@Getter
@Setter
@NoArgsConstructor
public class QsoDelegation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String delegationNo;

    /** 授权时的质量安全负责人任职。 */
    private Long appointmentId;

    private String delegateUsername;

    /** QsoDuty 代码，逗号分隔。 */
    private String duties;

    /** 具体事项。 */
    private String scope;

    private LocalDate validFrom;

    private LocalDate validTo;

    @Enumerated(EnumType.STRING)
    private DelegationStatus status;

    private String grantedBy;

    private LocalDateTime grantedAt;

    private String revokedBy;

    private LocalDateTime revokedAt;

    private String revokeReason;
}
