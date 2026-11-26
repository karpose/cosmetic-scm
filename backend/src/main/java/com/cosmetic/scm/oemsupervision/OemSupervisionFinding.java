package com.cosmetic.scm.oemsupervision;

import java.time.LocalDateTime;

import com.cosmetic.scm.domain.FindingSeverity;

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

/** 监督发现的一个问题。一般问题可以写当场纠正的情况，重大问题要转 CAPA。 */
@Entity
@Table(name = "oem_supervision_finding")
@Getter
@Setter
@NoArgsConstructor
public class OemSupervisionFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long supervisionId;

    private Integer seq;

    @Enumerated(EnumType.STRING)
    private FindingSeverity severity;

    private String description;

    private String correction;

    private Long capaId;

    private String createdBy;

    private LocalDateTime createdAt;
}
