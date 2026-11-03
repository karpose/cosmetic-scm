package com.cosmetic.scm.recall;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.CaseStatus;

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

/** 产品召回。由质量安全负责人发起和关闭，发起时冻结批次并按出库记录生成客户通知。 */
@Entity
@Table(name = "recall")
@Getter
@Setter
@NoArgsConstructor
public class Recall {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String recallNo;

    private Long batchId;

    private Long complaintId;

    private String reason;

    @Enumerated(EnumType.STRING)
    private CaseStatus status;

    private BigDecimal shippedQty;

    private String conclusion;

    private String openedByName;

    private LocalDateTime openedAt;

    private String closedByName;

    private LocalDateTime closedAt;
}
