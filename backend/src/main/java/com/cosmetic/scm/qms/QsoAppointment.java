package com.cosmetic.scm.qms;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.AppointmentStatus;
import com.cosmetic.scm.domain.MajorField;

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

/** 一位质量安全负责人的任职档案。任命文件、学历和经历证明在附件里。 */
@Entity
@Table(name = "qso_appointment")
@Getter
@Setter
@NoArgsConstructor
public class QsoAppointment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String appointNo;

    /** 对应的系统账号，要有质量安全负责人角色。 */
    private String username;

    private String personName;

    @Enumerated(EnumType.STRING)
    private MajorField majorField;

    private String majorDetail;

    private String education;

    /** 化妆品生产或质量管理经验年限。 */
    private Integer yearsExperience;

    private String experience;

    private LocalDate appointedOn;

    @Enumerated(EnumType.STRING)
    private AppointmentStatus status;

    /** 现任时为 ACTIVE，其余为空，唯一约束保证同一时间只有一位现任。 */
    private String openKey;

    private LocalDate endedOn;

    private String endReason;

    private String createdBy;

    private LocalDateTime createdAt;

    private String activatedBy;

    private LocalDateTime activatedAt;
}
