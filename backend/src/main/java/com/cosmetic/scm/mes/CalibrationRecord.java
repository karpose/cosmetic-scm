package com.cosmetic.scm.mes;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.CalibrationResult;

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

/** 校准记录，只增不改。 */
@Entity
@Table(name = "calibration_record")
@Getter
@Setter
@NoArgsConstructor
public class CalibrationRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long equipmentId;

    private LocalDate calibratedDate;

    @Enumerated(EnumType.STRING)
    private CalibrationResult result;

    private String certificateNo;

    private LocalDate nextDue;

    private String note;

    private String doneBy;

    private LocalDateTime doneAt;
}
