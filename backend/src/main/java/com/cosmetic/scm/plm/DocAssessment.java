package com.cosmetic.scm.plm;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.AssessMethod;

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

/** 一个人对一版受控文件的一次考核。没通过可以再考，以最近一次为准。 */
@Entity
@Table(name = "doc_assessment")
@Getter
@Setter
@NoArgsConstructor
public class DocAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long revisionId;

    private String username;

    @Enumerated(EnumType.STRING)
    private AssessMethod method;

    /** 分数，选填，系统不按分数判定。 */
    private Integer score;

    private boolean passed;

    private String note;

    private LocalDate assessedOn;

    private String assessedBy;

    private LocalDateTime createdAt;
}
