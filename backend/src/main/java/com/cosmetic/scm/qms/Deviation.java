package com.cosmetic.scm.qms;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.CaseStatus;
import com.cosmetic.scm.domain.DeviationArea;
import com.cosmetic.scm.domain.DeviationLevel;

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

/** 偏差。重大及以上的偏差未关闭时，关联批次不能放行；关闭须 CAPA 全部验证完成。 */
@Entity
@Table(name = "deviation")
@Getter
@Setter
@NoArgsConstructor
public class Deviation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String deviationNo;

    private LocalDate occurredDate;

    @Enumerated(EnumType.STRING)
    private DeviationArea area;

    @Enumerated(EnumType.STRING)
    private DeviationLevel level;

    private Long batchId;

    private Long moId;

    private String title;

    private String description;

    private String immediateAction;

    private String rootCause;

    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    private CaseStatus status;

    private String conclusion;

    private String closedBy;

    private LocalDateTime closedAt;

    private String createdBy;

    private LocalDateTime createdAt;
}
