package com.cosmetic.scm.plm;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.TrialKind;
import com.cosmetic.scm.domain.TrialResult;

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

/** 一次小试或中试。记录后不能改。 */
@Entity
@Table(name = "npd_trial")
@Getter
@Setter
@NoArgsConstructor
public class NpdTrial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long projectId;

    private String trialNo;

    @Enumerated(EnumType.STRING)
    private TrialKind kind;

    private LocalDate trialDate;

    private Long formulaVersionId;

    private String batchSize;

    @Enumerated(EnumType.STRING)
    private TrialResult result;

    private String summary;

    private String recordedBy;

    private LocalDateTime recordedAt;
}
