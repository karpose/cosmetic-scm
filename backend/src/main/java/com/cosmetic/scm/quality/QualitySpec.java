package com.cosmetic.scm.quality;

import java.time.LocalDateTime;

import com.cosmetic.scm.domain.SpecStatus;

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

/** 物料或成品的一个质量标准版本。签批后生效，旧版本自动替代。 */
@Entity
@Table(name = "quality_spec")
@Getter
@Setter
@NoArgsConstructor
public class QualitySpec {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String specNo;

    private Long itemId;

    private Integer versionNo;

    @Enumerated(EnumType.STRING)
    private SpecStatus status;

    private String changeNote;

    private String rejectNote;

    private String createdBy;

    private LocalDateTime createdAt;

    private String approvedBy;

    private LocalDateTime approvedAt;

    /** 取样要求，文字。系统不内置抽样方案。 */
    private String samplingPlan;

    /** 同一供方连续采用报告的批数上限，空 = 不限。设了以后首批也要全项自检。 */
    private Integer coaMaxStreak;
}
