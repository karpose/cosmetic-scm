package com.cosmetic.scm.settlement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.FinanceSyncStatus;

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

/** 客户应收对账单（AR）：覆盖销售出库对账、开票、审批、回款核销与外部财务应收凭证对接。 */
@Entity
@Table(name = "customer_statement")
@Getter
@Setter
@NoArgsConstructor
public class CustomerStatement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String statementNo;

    private Long customerId;

    private LocalDate periodTo;

    private int creditDays = 30;

    private LocalDate dueDate;

    /** DRAFT / INVOICED / APPROVED / PAID / CANCELLED */
    private String status = "DRAFT";

    private BigDecimal goodsAmount;

    private BigDecimal taxAmount;

    private BigDecimal adjustment = BigDecimal.ZERO;

    private String adjustmentNote;

    private BigDecimal totalAmount;

    private BigDecimal receivedAmount = BigDecimal.ZERO;

    private String invoiceNo;

    private LocalDate invoiceDate;

    private String approvedBy;

    private LocalDateTime approvedAt;

    @Enumerated(EnumType.STRING)
    private FinanceSyncStatus financeSyncStatus = FinanceSyncStatus.PENDING;

    private String extVoucherNo;

    private LocalDateTime financeSyncedAt;

    private String financeSyncNote;

    private String createdBy;

    private LocalDateTime createdAt;

    public BigDecimal outstandingAmount() {
        return totalAmount.subtract(receivedAmount);
    }

    public FinanceSyncStatus effectiveFinanceSyncStatus() {
        return financeSyncStatus != null ? financeSyncStatus : FinanceSyncStatus.PENDING;
    }

    public String statusLabel() {
        return switch (status) {
            case "DRAFT" -> "待开票";
            case "INVOICED" -> "待审批";
            case "APPROVED" -> "待回款";
            case "PAID" -> "已回款";
            case "CANCELLED" -> "已作废";
            default -> status;
        };
    }
}
