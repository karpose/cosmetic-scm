package com.cosmetic.scm.quality;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.Decision;
import com.cosmetic.scm.domain.QcStage;

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

/** 质量签批记录：物料检验放行、成品上市放行。只新增，不修改。 */
@Entity
@Table(name = "quality_decision")
@Getter
@Setter
@NoArgsConstructor
public class QualityDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long batchId;

    @Enumerated(EnumType.STRING)
    private QcStage stage;

    @Enumerated(EnumType.STRING)
    private Decision decision;

    private String reportNo;

    private String note;

    private String checklist;

    private Integer retainQty;

    private String retainLocation;

    private LocalDate retainUntil;

    private Long signedById;

    private String signedByName;

    private LocalDateTime signedAt;
}
