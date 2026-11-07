package com.cosmetic.scm.plm;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.RevisionStatus;

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

/** 受控文件的一个修订版本。签批后不能改，要改就再出一版。 */
@Entity
@Table(name = "doc_revision")
@Getter
@Setter
@NoArgsConstructor
public class DocRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long docId;

    private Integer revNo;

    private String changeSummary;

    private LocalDate effectiveDate;

    @Enumerated(EnumType.STRING)
    private RevisionStatus status;

    private String rejectNote;

    private String draftedBy;

    private LocalDateTime draftedAt;

    private String approvedBy;

    private LocalDateTime approvedAt;
}
