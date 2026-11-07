package com.cosmetic.scm.settlement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.StatementStatus;

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

/** 供应商对账单。 */
@Entity
@Table(name = "supplier_statement")
@Getter
@Setter
@NoArgsConstructor
public class SupplierStatement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String statementNo;

    private Long supplierId;

    private LocalDate periodTo;

    @Enumerated(EnumType.STRING)
    private StatementStatus status;

    private BigDecimal goodsAmount;

    private BigDecimal taxAmount;

    private BigDecimal adjustment;

    private String adjustmentNote;

    private BigDecimal totalAmount;

    private String disputeNote;

    private String cancelReason;

    private String confirmedBy;

    private LocalDateTime confirmedAt;

    private String invoiceNo;

    private LocalDate invoiceDate;

    private BigDecimal invoiceAmount;

    private String approvedBy;

    private LocalDateTime approvedAt;

    private BigDecimal paidAmount;

    private String createdBy;

    private LocalDateTime createdAt;
}
