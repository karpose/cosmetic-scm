package com.cosmetic.scm.settlement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.cosmetic.scm.domain.FinanceSyncStatus;
import com.cosmetic.scm.domain.StatementStatus;
import com.cosmetic.scm.domain.rules.SettlementRule;

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

/** 供应商对账单。支持信用账期、应付到期日、多税率拆分汇总与红字冲减对账。 */
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

    /** PURCHASE=采购来料货款对账，OUTSOURCING=委外加工费对账。 */
    private String statementType = "PURCHASE";

    private Long supplierId;

    private LocalDate periodTo;

    /** 信用账期天数（默认 30 天）。 */
    private int creditDays = 30;

    /** 应付到期日（periodTo + creditDays）。 */
    private LocalDate dueDate;

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
    private int invoiceReviewVersion;

    private String approvedBy;

    private LocalDateTime approvedAt;

    private BigDecimal paidAmount;

    private String createdBy;

    private LocalDateTime createdAt;

    @Enumerated(EnumType.STRING)
    private FinanceSyncStatus financeSyncStatus = FinanceSyncStatus.PENDING;

    private String extVoucherNo;

    private LocalDateTime financeSyncedAt;

    private String financeSyncNote;

    public FinanceSyncStatus effectiveFinanceSyncStatus() {
        return financeSyncStatus != null ? financeSyncStatus : FinanceSyncStatus.PENDING;
    }

    public String effectiveStatementType() {
        return statementType != null && !statementType.isBlank() ? statementType : "PURCHASE";
    }

    public String statementTypeLabel() {
        return "OUTSOURCING".equals(effectiveStatementType()) ? "委外加工费" : "采购货款";
    }

    /** V28 之前的单据没有单独存到期日。 */
    public LocalDate effectiveDueDate() {
        return dueDate != null ? dueDate : SettlementRule.computeDueDate(periodTo, creditDays);
    }

    public BigDecimal outstandingAmount() {
        return totalAmount.subtract(paidAmount);
    }

    public boolean invoiceCorrectionPending() {
        return status == StatementStatus.CONFIRMED && invoiceReviewVersion > 0;
    }

    public String statusLabel() {
        if (invoiceCorrectionPending()) return "发票待更正";
        if (totalAmount.signum() < 0) {
            if (status == StatementStatus.APPROVED) return "待供应商退款";
            if (status == StatementStatus.PAID) return "供应商已退款";
        }
        return status.getLabel();
    }
}
