package com.cosmetic.scm.quality;

import java.math.BigDecimal;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 一次检验里一个项目的结果。 */
@Entity
@Table(name = "batch_inspection_result")
@Getter
@Setter
@NoArgsConstructor
public class BatchInspectionResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long inspectionId;

    private Long specLineId;

    private BigDecimal valueNum;

    private String valueText;

    private boolean passed;
}
