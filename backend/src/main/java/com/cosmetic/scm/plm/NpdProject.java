package com.cosmetic.scm.plm;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.FilingType;
import com.cosmetic.scm.domain.NpdStatus;

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

/** 一个新品开发项目：立项审批 → 开发和试制 → 上市检查 → QSO 批准上市。 */
@Entity
@Table(name = "npd_project")
@Getter
@Setter
@NoArgsConstructor
public class NpdProject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String projectNo;

    private String name;

    private String category;

    @Enumerated(EnumType.STRING)
    private FilingType filingType;

    private String claims;

    private LocalDate targetLaunch;

    private String note;

    private Long fgItemId;

    @Enumerated(EnumType.STRING)
    private NpdStatus status;

    private String approvedBy;

    private LocalDateTime approvedAt;

    private String rejectNote;

    private String submittedBy;

    private LocalDateTime submittedAt;

    private String launchedBy;

    private LocalDateTime launchedAt;

    private String cancelReason;

    private String createdBy;

    private LocalDateTime createdAt;
}
