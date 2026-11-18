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

/** 付款与退款记录。paymentType: PAY（向供应商付款）或 REFUND（退货冲减大于货款时供应商退款）。 */
@Entity
@Table(name = "supplier_payment")
@Getter
@Setter
@NoArgsConstructor
public class SupplierPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long statementId;

    /** PAY=付款，REFUND=供应商退款。 */
    private String paymentType = "PAY";

    private LocalDate payDate;

    private BigDecimal amount;

    private String referenceNo;

    /** 外部财务/银企直连付款水单号，用于回调核销幂等。 */
    private String extPaymentNo;

    private String createdBy;

    private LocalDateTime createdAt;
}
