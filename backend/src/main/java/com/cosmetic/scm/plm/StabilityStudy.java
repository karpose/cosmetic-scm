package com.cosmetic.scm.plm;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.StabilityStudyStatus;
import com.cosmetic.scm.domain.StabilityType;

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

/** 一次稳定性试验：一个样品放在一个条件下。多个条件或多个批次就是多次试验。 */
@Entity
@Table(name = "stability_study")
@Getter
@Setter
@NoArgsConstructor
public class StabilityStudy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String studyNo;

    private Long fgItemId;

    private Long formulaVersionId;

    private Long batchId;

    private String sampleRef;

    @Enumerated(EnumType.STRING)
    private StabilityType studyType;

    private String conditionText;

    private LocalDate startDate;

    private String note;

    @Enumerated(EnumType.STRING)
    private StabilityStudyStatus status;

    private String conclusion;

    private Integer supportedMonths;

    private String submittedBy;

    private LocalDateTime submittedAt;

    private String signedBy;

    private LocalDateTime signedAt;

    private String rejectNote;

    private String cancelReason;

    private String createdBy;

    private LocalDateTime createdAt;
}
