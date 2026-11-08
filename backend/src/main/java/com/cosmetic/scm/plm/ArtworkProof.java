package com.cosmetic.scm.plm;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.ProofStatus;

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

/** 一家印刷供应商对一版设计稿的一次打样。样张照片或扫描件在附件里。 */
@Entity
@Table(name = "artwork_proof")
@Getter
@Setter
@NoArgsConstructor
public class ArtworkProof {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String proofNo;

    private Long artworkId;

    private Long supplierId;

    /** 同一版设计稿、同一家供应商的第几次打样。 */
    private Integer roundNo;

    /** 收到样张的日期。 */
    private LocalDate proofDate;

    @Enumerated(EnumType.STRING)
    private ProofStatus status;

    private String note;

    /** 签样时勾选的核对项，逗号分隔。 */
    private String checklist;

    /** 不合格或作废的原因。 */
    private String resultNote;

    private String createdBy;

    private LocalDateTime createdAt;

    private String signedBy;

    private LocalDateTime signedAt;
}
