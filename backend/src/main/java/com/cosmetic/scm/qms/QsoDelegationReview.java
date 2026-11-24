package com.cosmetic.scm.qms;

import java.time.LocalDate;
import java.time.LocalDateTime;

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

/** 质量安全负责人对协助履职情况的一次监督记录。 */
@Entity
@Table(name = "qso_delegation_review")
@Getter
@Setter
@NoArgsConstructor
public class QsoDelegationReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long delegationId;

    private LocalDate reviewDate;

    private String note;

    private String reviewedBy;

    private LocalDateTime createdAt;
}
