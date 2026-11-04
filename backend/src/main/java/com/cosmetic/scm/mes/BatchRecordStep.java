package com.cosmetic.scm.mes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.StepStatus;

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

/** 批生产记录的一道工序：发料时从工艺路线固化，之后按实际执行填写。 */
@Entity
@Table(name = "batch_record_step")
@Getter
@Setter
@NoArgsConstructor
public class BatchRecordStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long moId;

    private Integer stepNo;

    private String name;

    private String instruction;

    private boolean critical;

    private String ipcName;

    private BigDecimal ipcMin;

    private BigDecimal ipcMax;

    private String ipcUnit;

    @Enumerated(EnumType.STRING)
    private StepStatus status;

    private BigDecimal ipcValue;

    private Boolean ipcPass;

    private String note;

    private String operator;

    private LocalDateTime doneAt;

    private String reviewer;

    private LocalDateTime reviewedAt;

    private Long deviationId;
}
