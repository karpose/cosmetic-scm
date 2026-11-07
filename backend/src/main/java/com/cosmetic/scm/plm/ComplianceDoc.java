package com.cosmetic.scm.plm;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.ComplianceDocStatus;
import com.cosmetic.scm.domain.ComplianceDocType;

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

/** 产品的一份合规资料（安全评估、功效宣称依据、标签），对应某个配方版本。报告文件作为附件。 */
@Entity
@Table(name = "compliance_doc")
@Getter
@Setter
@NoArgsConstructor
public class ComplianceDoc {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String docNo;

    private Long fgItemId;

    private Long formulaVersionId;

    @Enumerated(EnumType.STRING)
    private ComplianceDocType docType;

    private String title;

    /** 功效宣称的内容，如“保湿”。 */
    private String claim;

    /** 评估人或出具机构。 */
    private String evaluator;

    private LocalDate docDate;

    private String note;

    @Enumerated(EnumType.STRING)
    private ComplianceDocStatus status;

    private String rejectNote;

    private String voidReason;

    private String signedBy;

    private LocalDateTime signedAt;

    private String createdBy;

    private LocalDateTime createdAt;
}
