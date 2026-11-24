package com.cosmetic.scm.qms;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.SelfCheckConclusion;
import com.cosmetic.scm.domain.SelfCheckStatus;

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

/** 一次 GMP 自查（按检查要点逐项检查）。 */
@Entity
@Table(name = "gmp_self_check")
@Getter
@Setter
@NoArgsConstructor
public class GmpSelfCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String checkNo;

    private String edition;

    private LocalDate periodFrom;

    private LocalDate periodTo;

    private String scope;

    @Enumerated(EnumType.STRING)
    private SelfCheckStatus status;

    /** 进行中时等于 edition，结束后为空，用唯一约束保证同时只有一份在进行。 */
    private String openKey;

    @Enumerated(EnumType.STRING)
    private SelfCheckConclusion conclusion;

    private String summary;

    private String rejectNote;

    private String cancelReason;

    private String createdBy;

    private LocalDateTime createdAt;

    private String submittedBy;

    private LocalDateTime submittedAt;

    private String approvedBy;

    private LocalDateTime approvedAt;
}
