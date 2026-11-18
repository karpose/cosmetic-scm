package com.cosmetic.scm.settlement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 对账单发票明细：支持一张对账单绑定多张发票（含蓝字发票 BLUE 与红字冲减发票 RED）。 */
@Entity
@Table(name = "supplier_statement_invoice")
@Getter
@Setter
@NoArgsConstructor
public class SupplierStatementInvoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long statementId;

    private Long supplierId;

    /** 为空表示当前提交，否则属于一次已退回的提交。 */
    private Long invoiceReturnId;

    private String invoiceNo;

    /** BLUE=蓝字发票（正数），RED=红字发票（负数冲减）。 */
    private String invoiceType = "BLUE";

    private LocalDate invoiceDate;

    private BigDecimal taxRate;

    private BigDecimal amount;

    private BigDecimal taxAmount;

    private String note;

    private String createdBy;

    private LocalDateTime createdAt;
}
