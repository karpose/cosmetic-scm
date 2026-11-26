package com.cosmetic.scm.recall;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.DrillStatus;

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

/** 一次召回演练（模拟召回）。不冻结批次、不发召回通知；要联系的对象在 recall_drill_line。 */
@Entity
@Table(name = "recall_drill")
@Getter
@Setter
@NoArgsConstructor
public class RecallDrill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String drillNo;

    private Long batchId;

    private String scenario;

    private Integer targetHours;

    private String balance;

    @Enumerated(EnumType.STRING)
    private DrillStatus status;

    private String summary;

    private LocalDateTime finishedAt;

    private Integer elapsedMinutes;

    private BigDecimal locatedPct;

    private Boolean met;

    private Long capaId;

    private String rejectNote;

    private String reviewNote;

    private String cancelReason;

    private String startedBy;

    private LocalDateTime startedAt;

    private String submittedBy;

    private LocalDateTime submittedAt;

    private String reviewedBy;

    private LocalDateTime reviewedAt;
}
