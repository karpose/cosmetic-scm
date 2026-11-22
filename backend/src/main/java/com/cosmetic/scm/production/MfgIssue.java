package com.cosmetic.scm.production;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 生产发料明细：记录首次发料（INITIAL）、超耗补料（SUPPLEMENT）及余料退库（RETURN，qty 为负）。 */
@Entity
@Table(name = "mfg_issue")
@Getter
@Setter
@NoArgsConstructor
public class MfgIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long moId;

    private Long batchId;

    private Long warehouseId;

    private Long itemId;

    private BigDecimal qty;

    private String issueType = "INITIAL";

    private String reason;

    private String createdBy;

    private LocalDateTime createdAt;
}
