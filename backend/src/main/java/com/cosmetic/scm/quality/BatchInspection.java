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

    /** 取样情况：数量、位置、取样人。 */
    private String sampleNote;

    /** 供方检验报告编号，有项目采用供方报告时必填。 */
    private String coaNo;

    /** 有项目采用了供方报告。全项自检的是 false，算连续批数时用。 */
    private boolean usedCoa;
}
