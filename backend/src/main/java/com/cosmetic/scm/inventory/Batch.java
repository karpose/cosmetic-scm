package com.cosmetic.scm.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.BatchStatus;
import com.cosmetic.scm.domain.SourceType;

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

/** 批次：原料、包材、成品统一管理。批号全局唯一，是追溯的主键。 */
@Entity
@Table(name = "batch")
@Getter
@Setter
@NoArgsConstructor
public class Batch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String batchNo;

    private Long itemId;

    private LocalDate mfgDate;

    private LocalDate expDate;

    @Enumerated(EnumType.STRING)
    private BatchStatus status;

    @Enumerated(EnumType.STRING)
    private SourceType sourceType;

    private String sourceNo;

    /** 供应商（采购批次）或生产方（成品批次）。 */
    private Long partnerId;

    /** 供应商来料批号或受托厂批号。 */
    private String extBatchNo;

    private BigDecimal qtyIn;

    /** 受托生产企业已完成出厂放行。 */
    private boolean oemReleased;

    private String freezeReason;

    @Enumerated(EnumType.STRING)
    private BatchStatus statusBeforeFreeze;

    private LocalDateTime createdAt;

    /** 包材放行时核对的设计稿版本。 */
    private Long artworkId;

    /** 包材放行时依据的供应商签样。 */
    private Long proofId;
}
