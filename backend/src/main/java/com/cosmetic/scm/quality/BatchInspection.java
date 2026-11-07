package com.cosmetic.scm.quality;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.InspectionStatus;

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

/** 一个批次按标准做的一次检验。提交后不能改，录错了由 QSO 作废重录。 */
@Entity
@Table(name = "batch_inspection")
@Getter
@Setter
@NoArgsConstructor
public class BatchInspection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String inspectionNo;

    private Long batchId;

    private Long specId;

    private LocalDate inspectedDate;

    private String inspector;

    private String note;

    private boolean passed;

    @Enumerated(EnumType.STRING)
    private InspectionStatus status;

    private String voidReason;

    private String voidedBy;

    private Long deviationId;

    private LocalDateTime createdAt;
}
