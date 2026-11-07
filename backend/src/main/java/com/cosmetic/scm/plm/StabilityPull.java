package com.cosmetic.scm.plm;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.StabilityPullStatus;

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

/** 一个取样时间点。提交后不能改。 */
@Entity
@Table(name = "stability_pull")
@Getter
@Setter
@NoArgsConstructor
public class StabilityPull {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long studyId;

    private String label;

    private Integer months;

    private Integer days;

    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    private StabilityPullStatus status;

    private LocalDate doneDate;

    private String testedBy;

    private LocalDateTime recordedAt;

    private String skipReason;

    private String note;

    private Boolean passed;

    private Long deviationId;
}
